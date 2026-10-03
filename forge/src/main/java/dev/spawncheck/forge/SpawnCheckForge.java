package dev.spawncheck.forge;

import dev.spawncheck.Platform;
import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.net.Payloads;
import dev.spawncheck.net.ServerHandler;
import dev.spawncheck.fake.FakeCommand;
import dev.spawncheck.fake.FakePlayers;
import java.nio.file.Path;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

/** Forge entrypoint (client and server): the network channel, the server command and the server-side hooks. The client side is in {@link SpawnCheckForgeClient}. */
@Mod(SpawnCheckMod.MOD_ID)
public final class SpawnCheckForge {
	private static final int PROTOCOL_VERSION = 1;

	// Optional, so a vanilla client can still join a server that has Spawn Check (and the other way round).
	static final SimpleChannel CHANNEL = ChannelBuilder.named(SpawnCheckMod.id("main"))
		.networkProtocolVersion(PROTOCOL_VERSION)
		.acceptedVersions(Channel.VersionTest.exact(PROTOCOL_VERSION).or(Channel.VersionTest.ACCEPT_MISSING))
		.optional()
		.simpleChannel()
		.play()
		.serverbound()
		.addMain(Payloads.ScanRequest.class, Payloads.ScanRequest.CODEC, (payload, ctx) -> ServerHandler.onScan(payload, ctx.getSender()))
		.addMain(Payloads.ExplainRequest.class, Payloads.ExplainRequest.CODEC, (payload, ctx) -> ServerHandler.onExplain(payload, ctx.getSender()))
		.addMain(Payloads.FakeRequest.class, Payloads.FakeRequest.CODEC, (payload, ctx) -> ServerHandler.onFake(payload, ctx.getSender()))
		// The client-side handlers are in SpawnCheckForgeClient (via the dedicated-server-safe ClientHandlers indirection).
		.clientbound()
		.addMain(Payloads.ScanResult.class, Payloads.ScanResult.CODEC, (payload, ctx) -> ClientHandlers.onScanResult(payload))
		.addMain(Payloads.FakePositions.class, Payloads.FakePositions.CODEC, (payload, ctx) -> ClientHandlers.onFakePositions(payload))
		.build();

	public SpawnCheckForge(final FMLJavaModLoadingContext context) {
		SpawnCheckMod.init(new Platform() {
			@Override
			public Path configDir() {
				return FMLPaths.CONFIGDIR.get();
			}

			@Override
			public boolean canSend(final ServerPlayer player, final CustomPacketPayload.Type<?> type) {
				return CHANNEL.isRemotePresent(player.connection.getConnection());
			}

			@Override
			public void send(final ServerPlayer player, final CustomPacketPayload payload) {
				CHANNEL.send(payload, PacketDistributor.PLAYER.with(player));
			}
		});

		RegisterCommandsEvent.BUS.addListener(event -> event.getDispatcher().register(FakeCommand.build()));
		TickEvent.ServerTickEvent.Post.BUS.addListener(event -> ServerHandler.onServerTick(event.server()));
		ServerStoppingEvent.BUS.addListener(event -> FakePlayers.removeAll());

		if (FMLEnvironment.dist == Dist.CLIENT) {
			SpawnCheckForgeClient.init();
		}
	}
}
