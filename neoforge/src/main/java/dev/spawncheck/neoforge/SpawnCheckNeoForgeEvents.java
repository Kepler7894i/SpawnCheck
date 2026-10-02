package dev.spawncheck.neoforge;

import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.fake.FakeCommand;
import dev.spawncheck.fake.FakePlayers;
import dev.spawncheck.net.Payloads;
import dev.spawncheck.net.ServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The loader events needed on both client and server: payload registration, the server command and the server-side hooks. */
@EventBusSubscriber(modid = SpawnCheckMod.MOD_ID)
public final class SpawnCheckNeoForgeEvents {
	private SpawnCheckNeoForgeEvents() {
	}

	@SubscribeEvent
	public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
		// Optional, so a vanilla client can still join a server that has Spawn Check (and the other way round).
		PayloadRegistrar registrar = event.registrar("1").optional();
		registrar.playToServer(Payloads.ScanRequest.TYPE, Payloads.ScanRequest.CODEC, (payload, ctx) -> ServerHandler.onScan(payload, (ServerPlayer) ctx.player()));
		registrar.playToServer(Payloads.ExplainRequest.TYPE, Payloads.ExplainRequest.CODEC, (payload, ctx) -> ServerHandler.onExplain(payload, (ServerPlayer) ctx.player()));
		registrar.playToServer(Payloads.FakeRequest.TYPE, Payloads.FakeRequest.CODEC, (payload, ctx) -> ServerHandler.onFake(payload, (ServerPlayer) ctx.player()));
		// The client-side handlers are registered in SpawnCheckNeoForgeClient.
		registrar.playToClient(Payloads.ScanResult.TYPE, Payloads.ScanResult.CODEC);
		registrar.playToClient(Payloads.FakePositions.TYPE, Payloads.FakePositions.CODEC);
	}

	@SubscribeEvent
	public static void registerCommands(final RegisterCommandsEvent event) {
		event.getDispatcher().register(FakeCommand.build());
	}

	@SubscribeEvent
	public static void onServerTick(final ServerTickEvent.Post event) {
		ServerHandler.onServerTick(event.getServer());
	}

	@SubscribeEvent
	public static void onServerStopping(final ServerStoppingEvent event) {
		FakePlayers.removeAll();
	}
}
