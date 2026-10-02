package dev.spawncheck.analysis;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Re-implements the checks vanilla's {@link NaturalSpawner} makes for one block position, so we can say
 * whether a mob WILL spawn there right now and, if not, every reason why.
 *
 * <p>Mirrors {@code NaturalSpawner.spawnCategoryForPosition} / {@code isValidSpawnPostitionForType} and the
 * chunk/cap gating in {@code ServerChunkCache.tickChunks}. Create one instance per request: it snapshots the
 * player list and lazily builds per-player local mob counts.
 */
public final class SpawnAnalyzer {
	/** Mob spawn rules (light levels etc.) are random, so we roll them a few times with fixed seeds. */
	public static final int ROLLS = 8;
	/** Natural spawns need the nearest player (and the world spawn point) to be farther than this. */
	public static final int NO_SPAWN_DISTANCE = 24;
	private static final double MIN_PLAYER_DISTANCE_SQR = (double) NO_SPAWN_DISTANCE * NO_SPAWN_DISTANCE;
	private static final double SPAWN_RANGE_SQR = 128.0 * 128.0;
	private static final int MAGIC_NUMBER = 17 * 17;

	private final ServerLevel level;
	private final NaturalSpawner.@Nullable SpawnState state;
	private final StructureManager structures;
	private final ChunkGenerator generator;
	private final List<ServerPlayer> activePlayers = new ArrayList<>();
	private final Map<Long, List<ServerPlayer>> playersNearChunk = new HashMap<>();
	private @Nullable Map<ServerPlayer, Object2IntOpenHashMap<MobCategory>> localCounts;

	public SpawnAnalyzer(final ServerLevel level) {
		this.level = level;
		this.state = level.getChunkSource().getLastSpawnState();
		this.structures = level.structureManager();
		this.generator = level.getChunkSource().getGenerator();
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator()) {
				this.activePlayers.add(player);
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Scanning
	// ------------------------------------------------------------------------------------------------

	public static final int MAX_CELLS = 30000;

	public List<Cell> scan(final BlockPos center, final Mode mode, final @Nullable EntityType<?> target, final int radius, final int vRadius) {
		List<Cell> cells = new ArrayList<>();
		int minY = Math.max(level.getMinY() + 1, center.getY() - vRadius);
		int maxY = Math.min(level.getMaxY(), center.getY() + vRadius);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
				if (chunk == null) {
					continue;
				}
				// Walk the column one 16-high section at a time so tall scans don't pay for all the empty sky.
				int y = minY;
				while (y <= maxY) {
					int sectionEnd = Math.min(maxY, y | 15);
					if (chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()) {
						// Nothing to stand on inside an air-only section, bar its bottom layer (which rests on the section below).
						if ((y & 15) != 0) {
							y = sectionEnd + 1;
							continue;
						}
						sectionEnd = y;
					}
					for (; y <= sectionEnd; y++) {
						pos.set(x, y, z);
						if (!isCandidateSpot(pos)) {
							continue;
						}
						byte status = statusFor(mode, target, pos);
						cells.add(new Cell(pos.immutable(), status));
						if (cells.size() >= MAX_CELLS) {
							return cells;
						}
					}
				}
			}
		}
		return cells;
	}

	private byte statusFor(final Mode mode, final @Nullable EntityType<?> target, final BlockPos pos) {
		switch (mode) {
			case ANY -> {
				for (EntityType<?> type : candidates(pos)) {
					if (evaluate(type, pos, null)) {
						return Cell.STATUS_YES;
					}
				}
				return Cell.STATUS_NO;
			}
			case MOB -> {
				return target != null && evaluate(target, pos, null) ? Cell.STATUS_YES : Cell.STATUS_NO;
			}
			default -> {
				if (target == null || !evaluate(target, pos, null)) {
					return Cell.STATUS_NO;
				}
				for (EntityType<?> type : candidates(pos)) {
					if (type != target && evaluate(type, pos, null)) {
						return Cell.STATUS_SHARED;
					}
				}
				return Cell.STATUS_YES;
			}
		}
	}

	/** Cheap pre-filter: an open spot with something to stand on (or a fluid for water/lava mobs). */
	private boolean isCandidateSpot(final BlockPos pos) {
		BlockState feet = level.getBlockState(pos);
		if (feet.isCollisionShapeFullBlock(level, pos)) {
			return false;
		}
		if (!feet.getFluidState().isEmpty()) {
			return true;
		}
		BlockPos below = pos.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	// ------------------------------------------------------------------------------------------------
	// Spawn lists
	// ------------------------------------------------------------------------------------------------

	private List<Weighted<MobSpawnSettings.SpawnerData>> mobsAt(final MobCategory category, final BlockPos pos, final Holder<Biome> biome) {
		if (NaturalSpawner.isInNetherFortressBounds(pos, level, category, structures)) {
			return net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure.FORTRESS_ENEMIES.unwrap();
		}
		return generator.getMobsAt(biome, structures, category, pos).unwrap();
	}

	/** Every entity type that appears in some spawn list at this position (biome + structure overrides). */
	public Set<EntityType<?>> candidates(final BlockPos pos) {
		Holder<Biome> biome = level.getBiome(pos);
		Set<EntityType<?>> result = new LinkedHashSet<>();
		for (MobCategory category : MobCategory.values()) {
			if (category == MobCategory.MISC) {
				continue;
			}
			for (Weighted<MobSpawnSettings.SpawnerData> entry : mobsAt(category, pos, biome)) {
				result.add(entry.value().type());
			}
		}
		return result;
	}

	private boolean inSpawnList(final EntityType<?> type, final BlockPos pos) {
		Holder<Biome> biome = level.getBiome(pos);
		for (Weighted<MobSpawnSettings.SpawnerData> entry : mobsAt(type.getCategory(), pos, biome)) {
			if (entry.value().type() == type) {
				return true;
			}
		}
		return false;
	}

	public String biomeName(final BlockPos pos) {
		return level.getBiome(pos).getRegisteredName();
	}

	// ------------------------------------------------------------------------------------------------
	// The main check
	// ------------------------------------------------------------------------------------------------

	/**
	 * Can {@code type} spawn naturally with its feet at {@code pos} right now?
	 *
	 * @param why if non-null, every failed requirement is appended (and checking continues); if null, returns at
	 *            the first failure.
	 */
	public boolean evaluate(final EntityType<?> type, final BlockPos pos, final @Nullable List<String> why) {
		int before = why == null ? 0 : why.size();
		MobCategory category = type.getCategory();
		String name = idOf(type);
		double x = pos.getX() + 0.5;
		double z = pos.getZ() + 0.5;

		// ---- Things that switch spawning off for the whole level / category.
		if (category == MobCategory.MISC) {
			if (!fail(why, name + " is in the MISC category, which never spawns naturally.")) {
				return false;
			}
		}
		if (!type.canSummon() && !fail(why, name + " cannot be summoned, so it never spawns naturally.")) {
			return false;
		}
		if (!level.getGameRules().get(GameRules.SPAWN_MOBS) && !fail(why, "Gamerule spawn_mobs is false: no natural spawning at all.")) {
			return false;
		}
		if (!type.isAllowedInPeaceful() && level.getDifficulty() == Difficulty.PEACEFUL
			&& !fail(why, "Difficulty is Peaceful, so " + name + " can't spawn.")) {
			return false;
		}
		if (!category.isFriendly() && !level.isSpawningMonsters() && !fail(why, "Hostile spawning is disabled on this level (difficulty/server setting).")) {
			return false;
		}
		if (state != null) {
			int globalMax = category.getMaxInstancesPerChunk() * state.getSpawnableChunkCount() / MAGIC_NUMBER;
			int global = state.getMobCategoryCounts().getInt(category);
			if (global >= globalMax && !fail(why, "Global " + category.getName() + " mob cap is full: " + global + "/" + globalMax
				+ " (cap = " + category.getMaxInstancesPerChunk() + " x " + state.getSpawnableChunkCount() + " spawnable chunks / 289).")) {
				return false;
			}
		}
		if (category.isPersistent() && why != null) {
			why.add("(Note: " + category.getName() + " mobs only attempt to spawn once every 400 ticks.)");
			before++; // informational, not a failure
		}

		// ---- Chunk / player requirements.
		ChunkPos chunkPos = ChunkPos.containing(pos);
		if (level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z()) == null) {
			fail(why, "This chunk is not loaded.");
			return why != null && why.size() == before;
		}
		if (!level.canSpawnEntitiesInChunk(chunkPos) && !fail(why, "This chunk isn't entity-ticking or is outside the world border, so nothing spawns in it.")) {
			return false;
		}

		List<ServerPlayer> near = playersNear(chunkPos);
		if (near.isEmpty()) {
			if (!fail(why, "No non-spectator player within 128 blocks of this chunk, so it is not a spawning chunk. (Spectators don't count; creative players do.)")) {
				return false;
			}
		} else if (!localCapOpen(near, category)) {
			if (!fail(why, "Local " + category.getName() + " mob cap is full for every nearby player (" + localCountText(near, category) + ").")) {
				return false;
			}
		}

		Player nearest = level.getNearestPlayer(x, pos.getY(), z, -1.0, false);
		if (nearest == null) {
			if (!fail(why, "There is no non-spectator player in this dimension.")) {
				return false;
			}
		} else {
			double distSqr = nearest.distanceToSqr(x, pos.getY(), z);
			double dist = Math.sqrt(distSqr);
			if (distSqr <= MIN_PLAYER_DISTANCE_SQR && !fail(why, "Nearest player is only " + fmt(dist) + " blocks away; mobs need more than 24.")) {
				return false;
			}
			double despawn = category.getDespawnDistance();
			if (!type.canSpawnFarFromPlayer() && distSqr > despawn * despawn
				&& !fail(why, "Nearest player is " + fmt(dist) + " blocks away; " + name + " needs one within " + (int) despawn + ".")) {
				return false;
			}
		}

		LevelData.RespawnData respawn = level.getRespawnData();
		if (respawn.dimension() == level.dimension() && respawn.pos().closerToCenterThan(new Vec3(x, pos.getY(), z), 24.0)
			&& !fail(why, "Within 24 blocks of the world spawn point, where mobs can't spawn.")) {
			return false;
		}

		// ---- Spawn list (biome / structure).
		if (!inSpawnList(type, pos) && !fail(why, "Biome " + biomeName(pos) + " has no " + category.getName() + " spawn entry for " + name
			+ " here (and no structure overrides it).")) {
			return false;
		}

		// ---- Block / space requirements.
		if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)) {
			if (why == null) {
				return false;
			}
			placementReasons(type, pos, why);
		}
		AABB box = type.getSpawnAABB(x, pos.getY(), z);
		if (!level.noCollision(box) && !fail(why, "Something solid or an entity overlaps its hitbox here (" + fmt(box.getXsize()) + " wide x " + fmt(box.getYsize())
			+ " tall" + (box.getXsize() > 1.0 ? "; wider than a block, so the neighbouring blocks must be clear too" : "") + ").")) {
			return false;
		}

		// ---- Mob-specific rules (light level, biome, y-level...). These use randomness, so roll several times.
		int passed = rollsPassed(type, pos);
		if (passed == 0) {
			if (why == null) {
				return false;
			}
			why.add(spawnRuleFailure(type, pos));
		}

		return why == null || why.size() == before;
	}

	private int rollsPassed(final EntityType<?> type, final BlockPos pos) {
		int passed = 0;
		for (int i = 0; i < ROLLS; i++) {
			if (rule(type, pos, new LegacyRandomSource(0x5EED + i * 7919L))) {
				passed++;
			}
		}
		return passed;
	}

	private <T extends Entity> boolean rule(final EntityType<T> type, final BlockPos pos, final LegacyRandomSource random) {
		return SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, random);
	}

	public int rollsPassedPublic(final EntityType<?> type, final BlockPos pos) {
		return rollsPassed(type, pos);
	}

	private String spawnRuleFailure(final EntityType<?> type, final BlockPos pos) {
		String name = idOf(type);
		int sky = level.getBrightness(LightLayer.SKY, pos);
		int block = level.getBrightness(LightLayer.BLOCK, pos);
		if (type.getCategory() == MobCategory.MONSTER) {
			int limit = level.dimensionType().monsterSpawnBlockLightLimit();
			StringBuilder sb = new StringBuilder("Spawn rule failed (light: sky ").append(sky).append(", block ").append(block).append(").");
			if (sky > 0) {
				sb.append(" Sky light must be ~0.");
			}
			if (limit < 15 && block > limit) {
				sb.append(" Block light must be <= ").append(limit).append('.');
			}
			return sb.toString();
		}
		return "Spawn rule failed (light: sky " + sky + ", block " + block + "); mob-specific light/biome/y-level/block requirement.";
	}

	private void placementReasons(final EntityType<?> type, final BlockPos pos, final List<String> why) {
		String name = idOf(type);
		if (!level.getWorldBorder().isWithinBounds(pos)) {
			why.add("Outside the world border.");
			return;
		}
		if (SpawnPlacements.getPlacementType(type) == SpawnPlacementTypes.ON_GROUND) {
			BlockPos below = pos.below();
			BlockState belowState = level.getBlockState(below);
			if (!belowState.isValidSpawn(level, below, type)) {
				why.add("Block below (" + blockId(belowState) + ") is not a valid spawn surface for " + name
					+ " (needs a sturdy top face, and some blocks like leaves/glass-types/ice variants refuse spawns).");
			}
			emptyReasons(type, pos, "feet level", why);
			emptyReasons(type, pos.above(), "head level (y+1)", why);
		} else if (SpawnPlacements.getPlacementType(type) == SpawnPlacementTypes.IN_WATER) {
			why.add(name + " spawns in water: needs a water source/flow at this block and no solid block above it.");
		} else if (SpawnPlacements.getPlacementType(type) == SpawnPlacementTypes.IN_LAVA) {
			why.add(name + " spawns in lava: needs lava at this block.");
		} else {
			why.add("Spawn position rejected by the placement rules.");
		}
	}

	private void emptyReasons(final EntityType<?> type, final BlockPos pos, final String label, final List<String> why) {
		BlockState state = level.getBlockState(pos);
		String block = blockId(state);
		if (state.isCollisionShapeFullBlock(level, pos)) {
			why.add("Block at " + label + " (" + block + ") has a full solid collision box.");
		} else if (state.isSignalSource()) {
			why.add("Block at " + label + " (" + block + ") is a redstone signal source, which blocks spawning.");
		} else if (!state.getFluidState().isEmpty()) {
			why.add("Block at " + label + " (" + block + ") contains fluid; ground mobs can't spawn in it.");
		} else if (state.is(BlockTags.PREVENT_MOB_SPAWNING_INSIDE)) {
			why.add("Block at " + label + " (" + block + ") is tagged prevent_mob_spawning_inside (e.g. rails).");
		} else if (type.isBlockDangerous(state)) {
			why.add("Block at " + label + " (" + block + ") is dangerous to " + idOf(type) + ".");
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Players, gates and caps
	// ------------------------------------------------------------------------------------------------

	private List<ServerPlayer> playersNear(final ChunkPos pos) {
		return playersNearChunk.computeIfAbsent(pos.pack(), key -> {
			List<ServerPlayer> result = new ArrayList<>();
			double cx = pos.x() * 16 + 8;
			double cz = pos.z() * 16 + 8;
			for (ServerPlayer player : activePlayers) {
				double dx = cx - player.getX();
				double dz = cz - player.getZ();
				if (dx * dx + dz * dz < SPAWN_RANGE_SQR) {
					result.add(player);
				}
			}
			return result;
		});
	}

	private Map<ServerPlayer, Object2IntOpenHashMap<MobCategory>> localCounts() {
		if (localCounts == null) {
			localCounts = new HashMap<>();
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof Mob mob) || mob.isPersistenceRequired() || mob.requiresCustomPersistence()) {
					continue;
				}
				MobCategory category = entity.getType().getCategory();
				if (category == MobCategory.MISC) {
					continue;
				}
				for (ServerPlayer player : playersNear(entity.chunkPosition())) {
					localCounts.computeIfAbsent(player, key -> new Object2IntOpenHashMap<>()).addTo(category, 1);
				}
			}
		}
		return localCounts;
	}

	private int localCount(final ServerPlayer player, final MobCategory category) {
		Object2IntOpenHashMap<MobCategory> counts = localCounts().get(player);
		return counts == null ? 0 : counts.getInt(category);
	}

	/** Vanilla: spawning is allowed if ANY nearby player is under the per-player cap. */
	private boolean localCapOpen(final List<ServerPlayer> near, final MobCategory category) {
		for (ServerPlayer player : near) {
			if (localCount(player, category) < category.getMaxInstancesPerChunk()) {
				return true;
			}
		}
		return false;
	}

	private String localCountText(final List<ServerPlayer> near, final MobCategory category) {
		StringBuilder sb = new StringBuilder();
		for (ServerPlayer player : near) {
			if (!sb.isEmpty()) {
				sb.append(", ");
			}
			sb.append(player.getGameProfile().name()).append(' ').append(localCount(player, category)).append('/').append(category.getMaxInstancesPerChunk());
		}
		return sb.toString();
	}

	/** Level-wide reasons nothing at all (or nothing hostile) can spawn. Empty list = gates are open. */
	public List<String> gateIssues(final ServerPlayer requester) {
		List<String> issues = new ArrayList<>();
		if (!level.getGameRules().get(GameRules.SPAWN_MOBS)) {
			issues.add("Gamerule spawn_mobs is false");
		}
		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			issues.add("Peaceful difficulty: no hostile mobs");
		} else if (!level.isSpawningMonsters()) {
			issues.add("Hostile spawning disabled on this level");
		}
		if (activePlayers.isEmpty()) {
			issues.add(requester.isSpectator()
				? "No non-spectator player (you're a spectator) - place one with /spawncheck fake"
				: "No non-spectator players in this dimension");
		}
		return issues;
	}

	/** Local counts come from the spawning player nearest {@code near} (the requester if nobody counts). */
	public List<CapInfo> caps(final ServerPlayer requester, final BlockPos near, final Collection<MobCategory> categories) {
		ServerPlayer player = requester;
		double best = Double.MAX_VALUE;
		for (ServerPlayer candidate : activePlayers) {
			double dist = candidate.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
			if (dist < best) {
				best = dist;
				player = candidate;
			}
		}
		List<CapInfo> result = new ArrayList<>();
		for (MobCategory category : categories) {
			int global = state == null ? 0 : state.getMobCategoryCounts().getInt(category);
			int globalMax = state == null ? 0 : category.getMaxInstancesPerChunk() * state.getSpawnableChunkCount() / MAGIC_NUMBER;
			result.add(new CapInfo(category.getName(), global, globalMax, localCount(player, category), category.getMaxInstancesPerChunk()));
		}
		return result;
	}

	public static List<MobCategory> spawningCategories() {
		List<MobCategory> result = new ArrayList<>();
		for (MobCategory category : MobCategory.values()) {
			if (category != MobCategory.MISC) {
				result.add(category);
			}
		}
		return result;
	}

	// ------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------

	/** Appends and returns true when collecting reasons; returns false when the caller should bail out. */
	private static boolean fail(final @Nullable List<String> why, final String message) {
		if (why == null) {
			return false;
		}
		why.add(message);
		return true;
	}

	public static String idOf(final EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
	}

	private static String blockId(final BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	private static String fmt(final double value) {
		return String.format("%.1f", value);
	}
}
