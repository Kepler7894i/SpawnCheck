package dev.spawncheck.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.analysis.CapInfo;
import dev.spawncheck.analysis.Cell;
import dev.spawncheck.analysis.Mode;
import dev.spawncheck.analysis.SpawnAnalyzer;
import dev.spawncheck.net.Payloads;
import dev.spawncheck.net.ServerHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The loader-independent client: keys, scanning, HUD, gizmos and the {@code /spawncheck} command. Each loader's client
 * entrypoint calls {@link #init} and wires the public hooks below into its own events.
 */
public final class SpawnCheckClient {
	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final int MAX_SCAN_INTERVAL_TICKS = 100;
	/** Blocks of scan volume the server is asked to cover per tick of refresh interval. */
	private static final long SCAN_VOLUME_PER_TICK = 20_000;
	/** Larger than any interval, so the next tick sends a scan request. */
	private static final int SCAN_NOW = Integer.MAX_VALUE / 2;
	private static final int GREEN = 0x8000FF40;
	private static final int RED = 0x70FF2020;
	private static final int YELLOW = 0x90FFD000;
	private static final int DESPAWN_FAR = 0xFFB060FF;
	private static final int DESPAWN_NEAR = 0xFFFF70D0;
	private static final int NO_SPAWN = 0xFFFF6040;
	/** The world spawn point's own no-spawn sphere, in a different shade so it isn't mistaken for a player's. */
	private static final int NO_SPAWN_WORLD = 0xFFFFA040;
	/** Straight segments per circle of the despawn spheres; at a 128 block radius this stays within ~0.15 blocks of a true circle. */
	private static final int SPHERE_SEGMENTS = 64;

	public static ClientConfig config;
	static ClientPlatform platform;
	private static Payloads.ScanResult latest;
	private static int ticksSinceScan;
	private static boolean serverMissing;
	/** Beyond this distance from every player a mob despawns at once. */
	static int farRadius = MobCategory.MONSTER.getDespawnDistance();
	/** Within this distance of a player a mob never despawns; beyond it, idle mobs can vanish at random. */
	static int nearRadius = MobCategory.MONSTER.getNoDespawnDistance();
	/** Where the server says the fake players in this dimension are. */
	private static List<Vec3> fakePositions = List.of();

	private static KeyMapping toggleKey;
	private static KeyMapping cycleKey;
	private static KeyMapping explainKey;

	private SpawnCheckClient() {
	}

	public static void init(final Path configDir, final ClientPlatform clientPlatform) {
		config = ClientConfig.load(configDir);
		platform = clientPlatform;
	}

	/** Id of the controls-screen category the keys are listed under. */
	public static Identifier keyCategoryId() {
		return SpawnCheckMod.id("main");
	}

	/** Id of the HUD layer. */
	public static Identifier hudId() {
		return SpawnCheckMod.id("hud");
	}

	/** Creates the key mappings (default keys V, B and N); the loader registers them. */
	public static List<KeyMapping> createKeys(final KeyMapping.Category category) {
		toggleKey = new KeyMapping("key.spawncheck.toggle", InputConstants.KEY_V, category);
		cycleKey = new KeyMapping("key.spawncheck.cycle_mode", InputConstants.KEY_B, category);
		explainKey = new KeyMapping("key.spawncheck.explain", InputConstants.KEY_N, category);
		return List.of(toggleKey, cycleKey, explainKey);
	}

	public static void onScanResult(final Payloads.ScanResult result) {
		latest = result;
	}

	public static void onFakePositions(final Payloads.FakePositions positions) {
		fakePositions = positions.positions();
	}

	// ------------------------------------------------------------------------------------------------
	// Tick: keys, requests, overlay
	// ------------------------------------------------------------------------------------------------

	public static void tick(final Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			latest = null;
			fakePositions = List.of();
			return;
		}
		MobCategory category = categoryFor(Mode.byId(config.mode), config.mob);
		farRadius = category.getDespawnDistance();
		nearRadius = category.getNoDespawnDistance();
		while (toggleKey.consumeClick()) {
			setEnabled(!config.enabled);
		}
		while (cycleKey.consumeClick()) {
			config.mode = Mode.byId(config.mode).next().ordinal();
			config.save();
			mc.player.sendOverlayMessage(Component.literal("Spawn mode: " + describeMode()));
			ticksSinceScan = SCAN_NOW;
		}
		while (explainKey.consumeClick()) {
			explain(mc);
		}

		if (!scanning()) {
			return;
		}
		if (!platform.canSend(Payloads.ScanRequest.TYPE)) {
			serverMissing = true;
			return;
		}
		serverMissing = false;
		if (++ticksSinceScan >= scanInterval(mc)) {
			ticksSinceScan = 0;
			platform.send(new Payloads.ScanRequest((byte) config.mode, config.mob, config.radius, config.height,
				config.useCenter, new BlockPos(config.cx, config.cy, config.cz)));
		}
	}

	/** The spots and the HUD both read the same server scan, so it only stops when both are off. */
	static boolean scanning() {
		return config.enabled || config.hud;
	}

	/** Big scans are expensive for the server, so the bigger the scanned volume, the less often it refreshes. */
	private static int scanInterval(final Minecraft mc) {
		long columns = 2L * config.radius + 1;
		long volume = columns * columns * Math.min(2L * config.height + 1, mc.level.getHeight());
		return (int) Math.max(SCAN_INTERVAL_TICKS, Math.min(MAX_SCAN_INTERVAL_TICKS, volume / SCAN_VOLUME_PER_TICK));
	}

	/** Drops the old results and requests a fresh scan on the next tick. */
	static void rescan() {
		latest = null;
		ticksSinceScan = SCAN_NOW;
	}

	/** Persists a change to {@code enabled}/{@code hud}, starting or discarding the scan if that switched it on or off. */
	static void visibilityChanged(final boolean wasScanning) {
		config.save();
		if (scanning() != wasScanning) {
			rescan();
		}
	}

	/** Category of the mob being checked, which sets its despawn distances (any mob mode: monsters). */
	private static MobCategory categoryFor(final Mode mode, final String mob) {
		EntityType<?> target = mode == Mode.ANY ? null : ServerHandler.parseMob(mob);
		return target == null ? MobCategory.MONSTER : target.getCategory();
	}

	/**
	 * Centre of the world spawn point's no-spawn sphere (nothing spawns within 24 blocks of it either), or null when it
	 * doesn't apply here: the spawn point is in another dimension, or outside the render distance.
	 */
	private static Vec3 worldSpawnCenter(final Minecraft mc) {
		LevelData.RespawnData respawn = mc.level.getRespawnData();
		if (!respawn.dimension().equals(mc.level.dimension())) {
			return null;
		}
		BlockPos pos = respawn.pos();
		ChunkPos here = mc.player.chunkPosition();
		int chunks = Math.max(Math.abs((pos.getX() >> 4) - here.x()), Math.abs((pos.getZ() >> 4) - here.z()));
		return chunks > mc.options.getEffectiveRenderDistance() ? null : Vec3.atCenterOf(pos);
	}

	/** Every position the despawn spheres are currently drawn round. */
	private static List<Vec3> sphereCenters(final Minecraft mc) {
		Around around = Around.byId(config.despawnAround);
		List<Vec3> centers = new ArrayList<>();
		if (around.includesYou()) {
			centers.add(mc.player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
		}
		if (around.includesFake()) {
			centers.addAll(fakePositions);
		}
		return centers;
	}

	/**
	 * Mobs despawn by the 3D distance to the nearest player, so the limits are spheres (not boxes: a cube's corners
	 * would sit well outside them). Drawn as latitude rings plus four circles through the poles.
	 */
	private static void emitSphere(final Vec3 center, final double radius, final int color) {
		for (int latitude = -60; latitude <= 60; latitude += 30) {
			double ring = radius * Math.cos(Math.toRadians(latitude));
			double y = center.y + radius * Math.sin(Math.toRadians(latitude));
			Vec3 previous = new Vec3(center.x + ring, y, center.z);
			for (int i = 1; i <= SPHERE_SEGMENTS; i++) {
				double angle = 2 * Math.PI * i / SPHERE_SEGMENTS;
				Vec3 next = new Vec3(center.x + ring * Math.cos(angle), y, center.z + ring * Math.sin(angle));
				Gizmos.line(previous, next, color, 2.0F);
				previous = next;
			}
		}
		for (int meridian = 0; meridian < 4; meridian++) {
			double heading = Math.PI * meridian / 4;
			double dx = Math.cos(heading);
			double dz = Math.sin(heading);
			Vec3 previous = new Vec3(center.x + radius * dx, center.y, center.z + radius * dz);
			for (int i = 1; i <= SPHERE_SEGMENTS; i++) {
				double angle = 2 * Math.PI * i / SPHERE_SEGMENTS;
				Vec3 next = new Vec3(center.x + radius * Math.cos(angle) * dx, center.y + radius * Math.sin(angle), center.z + radius * Math.cos(angle) * dz);
				Gizmos.line(previous, next, color, 2.0F);
				previous = next;
			}
		}
	}

	/** Submitted every frame from the level extraction event, where the renderer's gizmo collector is active. */
	public static void emitGizmos() {
		Minecraft mc = Minecraft.getInstance();
		if ((config.despawn || config.despawnNear || config.noSpawn) && mc.player != null) {
			for (Vec3 center : sphereCenters(mc)) {
				if (config.despawn) {
					emitSphere(center, farRadius, DESPAWN_FAR);
				}
				if (config.despawnNear) {
					emitSphere(center, nearRadius, DESPAWN_NEAR);
				}
				if (config.noSpawn) {
					emitSphere(center, SpawnAnalyzer.NO_SPAWN_DISTANCE, NO_SPAWN);
				}
			}
			if (config.noSpawn) {
				Vec3 spawn = worldSpawnCenter(mc);
				if (spawn != null) {
					emitSphere(spawn, SpawnAnalyzer.NO_SPAWN_DISTANCE, NO_SPAWN_WORLD);
				}
			}
		}
		Payloads.ScanResult result = latest;
		if (!config.enabled || result == null || result.denied()) {
			return;
		}
		boolean dense = result.cells().size() > 3000;
		for (Cell cell : result.cells()) {
			int color = switch (cell.status()) {
				case Cell.STATUS_YES -> GREEN;
				case Cell.STATUS_SHARED -> YELLOW;
				default -> RED;
			};
			BlockPos p = cell.pos();
			Gizmos.cuboid(new AABB(p.getX() + 0.1, p.getY(), p.getZ() + 0.1, p.getX() + 0.9, p.getY() + 0.05, p.getZ() + 0.9), dense ? GizmoStyle.fill(color) : GizmoStyle.strokeAndFill(color | 0xFF000000, 2.0F, color));
		}
	}

	static void explain(final Minecraft mc) {
		BlockPos pos = mc.player.blockPosition();
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			pos = hit.getBlockPos().relative(hit.getDirection());
		}
		explainAt(mc, pos);
	}

	static void explainAt(final Minecraft mc, final BlockPos pos) {
		if (!platform.canSend(Payloads.ExplainRequest.TYPE)) {
			mc.player.sendSystemMessage(Component.literal("This server doesn't have Spawn Check installed.").withStyle(ChatFormatting.RED));
			return;
		}
		platform.send(new Payloads.ExplainRequest(pos, (byte) config.mode, config.mob));
	}

	static void setEnabled(final boolean enabled) {
		boolean wasScanning = scanning();
		config.enabled = enabled;
		visibilityChanged(wasScanning);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal("Spawn spots " + (enabled ? "ON" : "OFF") + " (" + describeMode() + ")"));
		}
	}

	static String describeMode() {
		return switch (Mode.byId(config.mode)) {
			case ANY -> "any mob";
			case MOB -> config.mob + " (green = can spawn, red = can't)";
			case ONLY -> "only " + config.mob + " (green = only it, yellow = shared, red = it can't spawn)";
		};
	}

	// ------------------------------------------------------------------------------------------------
	// HUD
	// ------------------------------------------------------------------------------------------------

	static String aroundText() {
		String fakes = fakePositions.isEmpty() ? "no fake players" : fakePositions.size() + " fake player" + (fakePositions.size() == 1 ? "" : "s");
		return switch (Around.byId(config.despawnAround)) {
			case YOU -> "you";
			case FAKE -> fakes;
			case BOTH -> "you + " + fakes;
		};
	}

	public static void renderHud(final GuiGraphicsExtractor g, final DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!config.hud || mc.player == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		add(lines, colors, "Spawn Check: " + describeMode(), 0xFFFFFFFF);
		if (config.useCenter) {
			add(lines, colors, "Area: " + config.cx + " " + config.cy + " " + config.cz + " (r" + config.radius + ")", 0xFF55FFFF);
		}
		if (config.despawn) {
			add(lines, colors, "Despawn sphere: radius " + farRadius + " (" + 2 * farRadius + " across) round " + aroundText(), DESPAWN_FAR);
		}
		if (config.despawnNear) {
			add(lines, colors, "No-despawn sphere: radius " + nearRadius + " (" + 2 * nearRadius + " across) round " + aroundText(), DESPAWN_NEAR);
		}
		if (config.noSpawn) {
			int r = SpawnAnalyzer.NO_SPAWN_DISTANCE;
			add(lines, colors, "No-spawn sphere: radius " + r + " (" + 2 * r + " across) round " + aroundText()
				+ (worldSpawnCenter(mc) != null ? " + world spawn" : ""), NO_SPAWN);
		}

		Payloads.ScanResult r = latest;
		if (serverMissing) {
			add(lines, colors, "Server doesn't have Spawn Check", 0xFFFF5555);
		} else if (r == null) {
			add(lines, colors, "Waiting for server...", 0xFFAAAAAA);
		} else if (!r.error().isEmpty()) {
			add(lines, colors, r.error(), 0xFFFF5555);
		} else {
			if (r.issues().isEmpty()) {
				add(lines, colors, "Spawning gates open", 0xFF55FF55);
			}
			for (String issue : r.issues()) {
				add(lines, colors, "! " + issue, 0xFFFF5555);
			}
			for (CapInfo cap : r.caps()) {
				boolean full = cap.global() >= cap.globalMax() || cap.local() >= cap.localMax();
				add(lines, colors, cap.category() + ": global " + cap.global() + "/" + cap.globalMax() + "  local " + cap.local() + "/" + cap.localMax(),
					full ? 0xFFFFAA00 : 0xFFAAAAAA);
			}
			boolean capped = r.cells().size() >= SpawnCheckMod.MAX_CELLS;
			add(lines, colors, r.cells().size() + " spots " + (config.enabled ? "shown" : "found (hidden)")
				+ (r.cells().isEmpty() ? " (no open floor spots nearby)" : capped ? " - limit hit, part of the area is missing" : ""),
				capped ? 0xFFFFAA00 : 0xFF888888);
		}

		int y = 4;
		for (int i = 0; i < lines.size(); i++) {
			int width = mc.font.width(lines.get(i));
			g.text(mc.font, lines.get(i), g.guiWidth() - width - 4, y, colors.get(i));
			y += 10;
		}
	}

	private static void add(final List<String> lines, final List<Integer> colors, final String text, final int color) {
		lines.add(text);
		colors.add(color);
	}
}
