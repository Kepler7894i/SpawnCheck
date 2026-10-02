package dev.spawncheck;

import dev.spawncheck.analysis.SpawnAnalyzer;
import dev.spawncheck.fake.FakePlayers;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.spawncheck.net.Payloads;
import dev.spawncheck.net.ServerHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnCheckMod implements ModInitializer {
	public static final String MOD_ID = "spawncheck";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final int MAX_CELLS = SpawnAnalyzer.MAX_CELLS;
	/** Spawning only happens within 128 blocks of a player, so a wider scan would never show anything. */
	public static final int MAX_RADIUS = 128;
	/** Large enough to cover the tallest possible dimension (4064 blocks); the scan clamps to the real world height. */
	public static final int MAX_V_RADIUS = 2032;

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(Payloads.ScanRequest.TYPE, Payloads.ScanRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.ExplainRequest.TYPE, Payloads.ExplainRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.ScanResult.TYPE, Payloads.ScanResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.FakePositions.TYPE, Payloads.FakePositions.CODEC);
		ServerTickEvents.END_SERVER_TICK.register(ServerHandler::onServerTick);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.ScanRequest.TYPE, ServerHandler::onScan);
		ServerPlayNetworking.registerGlobalReceiver(Payloads.ExplainRequest.TYPE, ServerHandler::onExplain);

		PayloadTypeRegistry.serverboundPlay().register(Payloads.FakeRequest.TYPE, Payloads.FakeRequest.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Payloads.FakeRequest.TYPE, ServerHandler::onFake);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> FakePlayers.removeAll());
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(
			Commands.literal("spawncheckfake")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> placeFake(c.getSource(), BlockPos.containing(c.getSource().getPosition())))
				.then(Commands.literal("remove").executes(c -> {
					int removed = FakePlayers.removeAll();
					c.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " fake player(s)"), false);
					return removed;
				}))
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(c -> placeFake(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"))))));
	}

	private static int placeFake(final CommandSourceStack source, final BlockPos pos) {
		ServerPlayer fake = FakePlayers.spawn(source.getLevel(), pos);
		source.sendSuccess(() -> Component.literal("Placed fake player " + fake.getGameProfile().name() + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
		return 1;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
