package dev.spawncheck.fake;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Stand-in players that count for natural spawning (they are real, non-spectator {@link ServerPlayer}s in the
 * level) so a farm can be tested while the real player watches from spectator mode.
 */
public final class FakePlayers {
	private static final Map<String, FakePlayer> PLAYERS = new LinkedHashMap<>();
	private static int counter;

	private FakePlayers() {
	}

	/** Server-ticked player: no connection ticks it, so do the work vanilla's packet listener would. */
	private static final class FakePlayer extends ServerPlayer {
		private FakePlayer(final MinecraftServer server, final ServerLevel level, final GameProfile profile) {
			super(server, level, profile, ClientInformation.createDefault());
		}

		@Override
		public void tick() {
			if (this.connection != null && this.level().getServer().getTickCount() % 10 == 0) {
				this.connection.resetPosition();
				this.level().getChunkSource().move(this);
			}
			super.tick();
			this.doTick();
		}
	}

	public static ServerPlayer spawn(final ServerLevel level, final BlockPos pos) {
		MinecraftServer server = level.getServer();
		String name;
		do {
			name = "SpawnCheck" + (++counter);
		} while (PLAYERS.containsKey(name) || server.getPlayerList().getPlayerByName(name) != null);

		GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
		FakePlayer player = new FakePlayer(server, level, profile);
		player.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
		server.getPlayerList().placeNewPlayer(new FakeConnection(), player, CommonListenerCookie.createInitial(profile, false));

		// Creative: counts for spawning, can't be hurt, mobs don't target it. Flying: it never falls.
		player.setGameMode(GameType.CREATIVE);
		player.getAbilities().flying = true;
		player.onUpdateAbilities();
		player.setPermanentlyInvulnerable(true);
		PLAYERS.put(name, player);
		return player;
	}

	public static List<String> names() {
		return new ArrayList<>(PLAYERS.keySet());
	}

	public static boolean isEmpty() {
		return PLAYERS.isEmpty();
	}

	public static boolean isFake(final ServerPlayer player) {
		return PLAYERS.get(player.getGameProfile().name()) == player;
	}

	/** Where the fake players in {@code level} are standing. */
	public static List<Vec3> positionsIn(final ServerLevel level) {
		List<Vec3> result = new ArrayList<>();
		for (FakePlayer player : PLAYERS.values()) {
			if (player.level() == level) {
				result.add(player.position());
			}
		}
		return result;
	}

	public static int removeAll() {
		int count = 0;
		for (FakePlayer player : new ArrayList<>(PLAYERS.values())) {
			remove(player);
			count++;
		}
		PLAYERS.clear();
		return count;
	}

	/** Removes the fake player called {@code name}; false if there is none. */
	public static boolean remove(final String name) {
		FakePlayer player = PLAYERS.remove(name);
		if (player == null) {
			return false;
		}
		remove(player);
		return true;
	}

	private static void remove(final FakePlayer player) {
		if (player.connection != null) {
			player.connection.onDisconnect(new DisconnectionDetails(Component.literal("Spawn Check fake player removed")));
		} else {
			player.level().getServer().getPlayerList().remove(player);
		}
	}
}
