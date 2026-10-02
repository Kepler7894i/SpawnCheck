package dev.spawncheck.net;

import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.analysis.Cell;
import dev.spawncheck.analysis.CapInfo;
import dev.spawncheck.analysis.Mode;
import dev.spawncheck.analysis.SpawnAnalyzer;
import dev.spawncheck.fake.FakePlayers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/** Server-side handling of scan / explain requests. */
public final class ServerHandler {
	private static final int MIN_TICKS_BETWEEN_SCANS = 5;
	private static final int FAKE_SYNC_TICKS = 10;
	private static final Map<UUID, Long> LAST_SCAN = new HashMap<>();
	private static boolean fakesWereSent;

	private ServerHandler() {
	}

	private static boolean allowed(final ServerPlayer player) {
		return mayUse(player.level().getServer(), player);
	}

	private static boolean mayUse(final MinecraftServer server, final ServerPlayer player) {
		return server.isSingleplayer() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** Keeps clients that can use Spawn Check told where the fake players are, so they can draw despawn spheres round them. */
	public static void onServerTick(final MinecraftServer server) {
		if (server.getTickCount() % FAKE_SYNC_TICKS != 0) {
			return;
		}
		boolean any = !FakePlayers.isEmpty();
		if (!any && !fakesWereSent) {
			return;
		}
		fakesWereSent = any; // one last (empty) update after the final one is removed
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!FakePlayers.isFake(player) && mayUse(server, player) && SpawnCheckMod.platform().canSend(player, Payloads.FakePositions.TYPE)) {
				SpawnCheckMod.platform().send(player, new Payloads.FakePositions(FakePlayers.positionsIn(player.level())));
			}
		}
	}

	public static @Nullable EntityType<?> parseMob(final String text) {
		Identifier id = Identifier.tryParse(text.trim().toLowerCase());
		return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
	}

	public static void onScan(final Payloads.ScanRequest request, final ServerPlayer player) {
		if (!allowed(player)) {
			SpawnCheckMod.platform().send(player, new Payloads.ScanResult(true, "You need operator permission to use Spawn Check on this server.", List.of(), List.of(), List.of()));
			return;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		Long last = LAST_SCAN.get(player.getUUID());
		if (last != null && now - last < MIN_TICKS_BETWEEN_SCANS) {
			return;
		}
		LAST_SCAN.put(player.getUUID(), now);

		Mode mode = Mode.byId(request.mode());
		EntityType<?> target = mode == Mode.ANY ? null : parseMob(request.mob());
		if (mode != Mode.ANY && target == null) {
			SpawnCheckMod.platform().send(player, new Payloads.ScanResult(false, "Unknown mob '" + request.mob() + "'", List.of(), List.of(), List.of()));
			return;
		}

		SpawnAnalyzer analyzer = new SpawnAnalyzer(level);
		int radius = Math.max(1, Math.min(SpawnCheckMod.MAX_RADIUS, request.radius()));
		int vRadius = Math.max(1, Math.min(SpawnCheckMod.MAX_V_RADIUS, request.vRadius()));
		BlockPos center = request.hasCenter() ? request.center() : player.blockPosition();
		if (request.hasCenter() && level.getChunkSource().getChunkNow(center.getX() >> 4, center.getZ() >> 4) == null) {
			SpawnCheckMod.platform().send(player, new Payloads.ScanResult(false, "Chunk at " + center.getX() + " " + center.getY() + " " + center.getZ() + " is not loaded", List.of(), List.of(), List.of()));
			return;
		}
		List<Cell> cells = analyzer.scan(center, mode, target, radius, vRadius);
		List<MobCategory> categories = target == null ? SpawnAnalyzer.spawningCategories() : List.of(target.getCategory());
		List<CapInfo> caps = analyzer.caps(player, center, categories);
		SpawnCheckMod.platform().send(player, new Payloads.ScanResult(false, "", analyzer.gateIssues(player), caps, cells));
	}

	public static void onFake(final Payloads.FakeRequest request, final ServerPlayer player) {
		if (!allowed(player)) {
			send(player, ChatFormatting.RED, "Spawn Check: you need operator permission on this server.");
			return;
		}
		if (request.action() == Payloads.FakeRequest.REMOVE_ALL) {
			int removed = FakePlayers.removeAll();
			send(player, ChatFormatting.YELLOW, removed == 0 ? "No fake players to remove." : "Removed " + removed + " fake player(s).");
			return;
		}
		if (request.action() == Payloads.FakeRequest.REMOVE_ONE) {
			boolean removed = FakePlayers.remove(request.name());
			send(player, removed ? ChatFormatting.YELLOW : ChatFormatting.RED,
				removed ? "Removed fake player " + request.name() + "." : "No fake player named " + request.name() + ".");
			return;
		}
		BlockPos pos = request.hasPos() ? request.pos() : player.blockPosition();
		ServerLevel level = player.level();
		if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
			send(player, ChatFormatting.RED, "That height is outside the world.");
			return;
		}
		ServerPlayer fake = FakePlayers.spawn(level, pos);
		send(player, ChatFormatting.GREEN, "Placed fake player " + fake.getGameProfile().name() + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
			+ ". Switch to spectator so only it counts; mobs spawn >24 blocks from it.");
	}

	public static void onExplain(final Payloads.ExplainRequest request, final ServerPlayer player) {
		if (!allowed(player)) {
			player.sendSystemMessage(Component.literal("Spawn Check: you need operator permission on this server.").withStyle(ChatFormatting.RED));
			return;
		}
		ServerLevel level = player.level();
		SpawnAnalyzer analyzer = new SpawnAnalyzer(level);
		BlockPos pos = request.pos();
		Mode mode = Mode.byId(request.mode());
		EntityType<?> target = mode == Mode.ANY ? null : parseMob(request.mob());
		if (mode != Mode.ANY && target == null) {
			player.sendSystemMessage(Component.literal("Spawn Check: unknown mob '" + request.mob() + "'").withStyle(ChatFormatting.RED));
			return;
		}

		send(player, ChatFormatting.GOLD, "Spawn check @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (" + analyzer.biomeName(pos) + ")");
		List<String> issues = analyzer.gateIssues(player);
		if (!issues.isEmpty()) {
			send(player, ChatFormatting.RED, "! " + String.join("; ", issues));
		}

		if (target != null) {
			explainOne(player, analyzer, target, pos);
			if (mode == Mode.ONLY) {
				// Show why each rival can't spawn here too, so a green spot can be traced back (e.g. a spider's wide hitbox not fitting).
				List<EntityType<?>> rivals = analyzer.candidates(pos).stream().filter(type -> type != target).toList();
				Evaluation others = evaluateAll(analyzer, rivals, pos);
				if (others.can().isEmpty()) {
					send(player, ChatFormatting.GREEN, "No other mob can spawn here.");
				} else {
					send(player, ChatFormatting.YELLOW, "Also can spawn: " + String.join(", ", others.can()));
				}
				sendReasons(player, others);
			}
		} else {
			Evaluation all = evaluateAll(analyzer, analyzer.candidates(pos), pos);
			if (all.can().isEmpty()) {
				send(player, ChatFormatting.RED, "Nothing can spawn here.");
			} else {
				send(player, ChatFormatting.GREEN, "Can spawn: " + String.join(", ", all.can()));
			}
			sendReasons(player, all);
		}

		MobCategory category = target == null ? MobCategory.MONSTER : target.getCategory();
		for (CapInfo cap : analyzer.caps(player, pos, List.of(category))) {
			send(player, ChatFormatting.AQUA, "Cap (" + cap.category() + "): global " + cap.global() + "/" + cap.globalMax()
				+ ", local " + cap.local() + "/" + cap.localMax());
		}
	}

	/** Which of a set of mobs can spawn at a position, and for those that can't, reason -> mobs it applies to. */
	private record Evaluation(List<String> can, Map<String, List<String>> reasons, int cannotCount) {
	}

	private static Evaluation evaluateAll(final SpawnAnalyzer analyzer, final Collection<EntityType<?>> types, final BlockPos pos) {
		List<String> can = new ArrayList<>();
		Map<String, List<String>> reasons = new LinkedHashMap<>();
		int cannotCount = 0;
		for (EntityType<?> type : types) {
			List<String> why = new ArrayList<>();
			String name = SpawnAnalyzer.idOf(type);
			if (analyzer.evaluate(type, pos, why)) {
				can.add(name);
			} else {
				cannotCount++;
				for (String reason : why) {
					if (!reason.startsWith("(")) {
						reasons.computeIfAbsent(reason.replace(name, "this mob"), key -> new ArrayList<>()).add(name);
					}
				}
			}
		}
		return new Evaluation(can, reasons, cannotCount);
	}

	private static void sendReasons(final ServerPlayer player, final Evaluation evaluation) {
		final int total = evaluation.cannotCount();
		evaluation.reasons().entrySet().stream()
			.sorted((a, b) -> b.getValue().size() - a.getValue().size())
			.forEach(entry -> send(player, ChatFormatting.GRAY, "x " + entry.getKey() + " ["
				+ (entry.getValue().size() == total && total > 1 ? "all others" : String.join(", ", entry.getValue())) + "]"));
	}

	private static void explainOne(final ServerPlayer player, final SpawnAnalyzer analyzer, final EntityType<?> type, final BlockPos pos) {
		List<String> why = new ArrayList<>();
		boolean ok = analyzer.evaluate(type, pos, why);
		String name = SpawnAnalyzer.idOf(type);
		if (ok) {
			int rolls = analyzer.rollsPassedPublic(type, pos);
			send(player, ChatFormatting.GREEN, "OK: " + name + " can spawn (rule passes " + rolls + "/" + SpawnAnalyzer.ROLLS + " rolls)");
			return;
		}
		send(player, ChatFormatting.RED, "NO: " + name + " can't spawn:");
		for (String line : why) {
			if (!line.startsWith("(")) {
				send(player, ChatFormatting.RED, " - " + line.replace(name, "it"));
			}
		}
	}

	private static void send(final ServerPlayer player, final ChatFormatting color, final String text) {
		player.sendSystemMessage(Component.literal(text).withStyle(color));
	}
}
