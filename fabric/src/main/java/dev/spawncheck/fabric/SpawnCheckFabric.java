package dev.spawncheck.fabric;

import dev.spawncheck.Platform;
import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.fake.FakeCommand;
import dev.spawncheck.fake.FakePlayers;
import dev.spawncheck.net.Payloads;
import dev.spawncheck.net.ServerHandler;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Fabric entrypoint (client and server): registers the payloads, the server command and the server-side events. */
public class SpawnCheckFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		SpawnCheckMod.init(new Platform() {
			@Override
			public Path configDir() {
				return FabricLoader.getInstance().getConfigDir();
			}

			@Override
			public boolean canSend(final ServerPlayer player, final CustomPacketPayload.Type<?> type) {
				return ServerPlayNetworking.canSend(player, type);
			}

			@Override
			public void send(final ServerPlayer player, final CustomPacketPayload payload) {
				ServerPlayNetworking.send(player, payload);
			}
		});

		PayloadTypeRegistry.serverboundPlay().register(Payloads.ScanRequest.TYPE, Payloads.ScanRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.ExplainRequest.TYPE, Payloads.ExplainRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.FakeRequest.TYPE, Payloads.FakeRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.ScanResult.TYPE, Payloads.ScanResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.FakePositions.TYPE, Payloads.FakePositions.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.ScanRequest.TYPE, (payload, ctx) -> ServerHandler.onScan(payload, ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.ExplainRequest.TYPE, (payload, ctx) -> ServerHandler.onExplain(payload, ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.FakeRequest.TYPE, (payload, ctx) -> ServerHandler.onFake(payload, ctx.player()));

		ServerTickEvents.END_SERVER_TICK.register(ServerHandler::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> FakePlayers.removeAll());
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(FakeCommand.build()));
	}
}
