package dev.spawncheck.neoforge;

import dev.spawncheck.Platform;
import dev.spawncheck.SpawnCheckMod;
import java.nio.file.Path;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge entrypoint (client and server). The events themselves are in {@link SpawnCheckNeoForgeEvents} and, client-only, {@link SpawnCheckNeoForgeClient}. */
@Mod(SpawnCheckMod.MOD_ID)
public class SpawnCheckNeoForge {
	public SpawnCheckNeoForge() {
		SpawnCheckMod.init(new Platform() {
			@Override
			public Path configDir() {
				return FMLPaths.CONFIGDIR.get();
			}

			@Override
			public boolean canSend(final ServerPlayer player, final CustomPacketPayload.Type<?> type) {
				return player.connection.hasChannel(type);
			}

			@Override
			public void send(final ServerPlayer player, final CustomPacketPayload payload) {
				PacketDistributor.sendToPlayer(player, payload);
			}
		});
		if (FMLEnvironment.getDist().isClient()) {
			SpawnCheckNeoForgeClient.init();
		}
	}
}
