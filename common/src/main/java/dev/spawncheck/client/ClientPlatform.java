package dev.spawncheck.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** What the mod loaders provide to the loader-independent client code: talking to the server. */
public interface ClientPlatform {
	/** Does the server we are connected to have Spawn Check (i.e. will it accept this payload)? */
	boolean canSend(CustomPacketPayload.Type<?> type);

	void send(CustomPacketPayload payload);
}
