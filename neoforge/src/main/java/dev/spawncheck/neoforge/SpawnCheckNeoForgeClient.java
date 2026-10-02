package dev.spawncheck.neoforge;

import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.client.ClientPlatform;
import dev.spawncheck.client.SpawnCheckClient;
import dev.spawncheck.client.SpawnCheckCommand;
import dev.spawncheck.net.Payloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** NeoForge client events: wires the shared client code into NeoForge's keys, HUD layer, render, tick, network and command events. */
@EventBusSubscriber(modid = SpawnCheckMod.MOD_ID, value = Dist.CLIENT)
public final class SpawnCheckNeoForgeClient {
	private SpawnCheckNeoForgeClient() {
	}

	static void init() {
		SpawnCheckClient.init(FMLPaths.CONFIGDIR.get(), new ClientPlatform() {
			@Override
			public boolean canSend(final CustomPacketPayload.Type<?> type) {
				var connection = Minecraft.getInstance().getConnection();
				return connection != null && connection.hasChannel(type);
			}

			@Override
			public void send(final CustomPacketPayload payload) {
				ClientPacketDistributor.sendToServer(payload);
			}
		});
	}

	@SubscribeEvent
	public static void registerKeys(final RegisterKeyMappingsEvent event) {
		KeyMapping.Category category = new KeyMapping.Category(SpawnCheckClient.keyCategoryId());
		event.registerCategory(category);
		SpawnCheckClient.createKeys(category).forEach(event::register);
	}

	@SubscribeEvent
	public static void registerHud(final RegisterGuiLayersEvent event) {
		event.registerAboveAll(SpawnCheckClient.hudId(), SpawnCheckClient::renderHud);
	}

	@SubscribeEvent
	public static void registerPayloadHandlers(final RegisterClientPayloadHandlersEvent event) {
		event.register(Payloads.ScanResult.TYPE, (payload, ctx) -> SpawnCheckClient.onScanResult(payload));
		event.register(Payloads.FakePositions.TYPE, (payload, ctx) -> SpawnCheckClient.onFakePositions(payload));
	}

	@SubscribeEvent
	public static void onClientTick(final ClientTickEvent.Post event) {
		SpawnCheckClient.tick(Minecraft.getInstance());
	}

	/** Fired where the renderer collects gizmos (right after the vanilla debug renderers emit theirs). */
	@SubscribeEvent
	public static void onExtractLevel(final ExtractLevelRenderStateEvent event) {
		SpawnCheckClient.emitGizmos();
	}

	@SubscribeEvent
	public static void registerCommands(final RegisterClientCommandsEvent event) {
		SpawnCheckCommand<CommandSourceStack> command = new SpawnCheckCommand<>(new SpawnCheckCommand.Feedback<>() {
			@Override
			public void info(final CommandSourceStack source, final Component message) {
				source.sendSystemMessage(message);
			}

			@Override
			public void error(final CommandSourceStack source, final Component message) {
				source.sendFailure(message);
			}
		});
		event.getDispatcher().register(command.build());
	}
}
