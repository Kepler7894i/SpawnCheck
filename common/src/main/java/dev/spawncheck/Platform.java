package dev.spawncheck;

import java.nio.file.Path;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** What the mod loaders (Fabric, NeoForge) provide to the loader-independent code. */
public interface Platform {
	Path configDir();

	/** Can this player's connection receive the payload (i.e. does their client have Spawn Check)? */
	boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

	void send(ServerPlayer player, CustomPacketPayload payload);
}
