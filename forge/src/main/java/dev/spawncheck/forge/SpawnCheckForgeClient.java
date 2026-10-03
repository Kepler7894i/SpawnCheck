package dev.spawncheck.forge;

import com.mojang.blaze3d.framegraph.FramePass;
import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.client.ClientPlatform;
import dev.spawncheck.client.SpawnCheckClient;
import dev.spawncheck.client.SpawnCheckCommand;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;

/** Forge client events: wires the shared client code into Forge's keys, HUD layer, render, tick and command events. */
final class SpawnCheckForgeClient {
	private SpawnCheckForgeClient() {
	}

	static void init() {
		SpawnCheckClient.init(FMLPaths.CONFIGDIR.get(), new ClientPlatform() {
			@Override
			public boolean canSend(final CustomPacketPayload.Type<?> type) {
				var connection = Minecraft.getInstance().getConnection();
				return connection != null && SpawnCheckForge.CHANNEL.isRemotePresent(connection.getConnection());
			}

			@Override
			public void send(final CustomPacketPayload payload) {
				SpawnCheckForge.CHANNEL.send(payload, PacketDistributor.SERVER.noArg());
			}
		});

		RegisterKeyMappingsEvent.BUS.addListener(event -> {
			KeyMapping.Category category = new KeyMapping.Category(SpawnCheckClient.keyCategoryId());
			SpawnCheckClient.createKeys(category).forEach(event::register);
		});
		AddGuiOverlayLayersEvent.BUS.addListener(event -> event.getLayeredDraw().add(SpawnCheckClient.hudId(), SpawnCheckClient::renderHud));
		TickEvent.ClientTickEvent.Post.BUS.addListener(event -> SpawnCheckClient.tick(Minecraft.getInstance()));
		AddFramePassEvent.BUS.addListener(SpawnCheckForgeClient::addGizmoPass);
		RegisterClientCommandsEvent.BUS.addListener(SpawnCheckForgeClient::registerCommands);
	}

	/** Forge has no extraction event, so the gizmos are emitted from a (do-nothing) frame pass while the renderer collects them. */
	private static void addGizmoPass(final AddFramePassEvent event) {
		event.addPass(SpawnCheckMod.id("gizmos"), new FramePassManager.PassDefinition() {
			@Override
			public void extracts(final LevelTargetBundle bundle, final FramePass pass, final LevelRenderState state) {
				bundle.main = pass.readsAndWrites(bundle.main);
			}

			@Override
			public void executes(final LevelRenderState state) {
				SpawnCheckClient.emitGizmos();
			}
		});
	}

	private static void registerCommands(final RegisterClientCommandsEvent event) {
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
