package dev.spawncheck.fabric;

import dev.spawncheck.client.ClientPlatform;
import dev.spawncheck.client.SpawnCheckClient;
import dev.spawncheck.client.SpawnCheckCommand;
import dev.spawncheck.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fabric client entrypoint: wires the shared client code into Fabric's keys, HUD, render, tick, network and command events. */
public class SpawnCheckFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SpawnCheckClient.init(FabricLoader.getInstance().getConfigDir(), new ClientPlatform() {
			@Override
			public boolean canSend(final CustomPacketPayload.Type<?> type) {
				return ClientPlayNetworking.canSend(type);
			}

			@Override
			public void send(final CustomPacketPayload payload) {
				ClientPlayNetworking.send(payload);
			}
		});

		KeyMapping.Category category = KeyMapping.Category.register(SpawnCheckClient.keyCategoryId());
		SpawnCheckClient.createKeys(category).forEach(KeyMappingHelper::registerKeyMapping);

		ClientPlayNetworking.registerGlobalReceiver(Payloads.ScanResult.TYPE, (payload, ctx) -> SpawnCheckClient.onScanResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.FakePositions.TYPE, (payload, ctx) -> SpawnCheckClient.onFakePositions(payload));

		ClientTickEvents.END_CLIENT_TICK.register(SpawnCheckClient::tick);
		LevelRenderEvents.END_EXTRACTION.register(context -> SpawnCheckClient.emitGizmos());
		HudElementRegistry.addLast(SpawnCheckClient.hudId(), SpawnCheckClient::renderHud);

		SpawnCheckCommand<FabricClientCommandSource> command = new SpawnCheckCommand<>(new SpawnCheckCommand.Feedback<>() {
			@Override
			public void info(final FabricClientCommandSource source, final Component message) {
				source.sendFeedback(message);
			}

			@Override
			public void error(final FabricClientCommandSource source, final Component message) {
				source.sendError(message);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(command.build()));
	}
}
