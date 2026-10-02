package dev.spawncheck.client;

import static dev.spawncheck.client.SpawnCheckClient.config;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.spawncheck.SpawnCheckMod;
import dev.spawncheck.analysis.Mode;
import dev.spawncheck.analysis.SpawnAnalyzer;
import dev.spawncheck.net.Payloads;
import dev.spawncheck.net.ServerHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The {@code /spawncheck} client command. It is written against any command-source type {@code S} so every loader can
 * register it on its own client command dispatcher; the loader only says how to show a message to the player.
 */
public final class SpawnCheckCommand<S> {
	/** How a loader shows a command's result, or its error, to the player. */
	public interface Feedback<S> {
		void info(S source, Component message);

		void error(S source, Component message);
	}

	/** Fake players are named this plus a number (see FakePlayers.spawn). */
	private static final String FAKE_PREFIX = "SpawnCheck";

	private final Feedback<S> feedback;

	public SpawnCheckCommand(final Feedback<S> feedback) {
		this.feedback = feedback;
	}

	private LiteralArgumentBuilder<S> literal(final String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private <T> RequiredArgumentBuilder<S, T> argument(final String name, final ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	public LiteralArgumentBuilder<S> build() {
		return literal("spawncheck")
			.then(literal("toggle").executes(c -> {
				SpawnCheckClient.setEnabled(!config.enabled);
				return 1;
			}))
			.then(literal("hud").executes(c -> {
				boolean wasScanning = SpawnCheckClient.scanning();
				config.hud = !config.hud;
				SpawnCheckClient.visibilityChanged(wasScanning);
				feedback.info(c.getSource(), Component.literal("HUD " + (config.hud ? "on" : "off")));
				return 1;
			}))
			// despawn = the instant-despawn sphere (far); near = the no-despawn sphere; around = whose position they're centred on.
			.then(literal("despawn")
				.executes(c -> {
					config.despawn = !config.despawn;
					return despawnChanged(c.getSource());
				})
				.then(literal("far").executes(c -> {
					config.despawn = !config.despawn;
					return despawnChanged(c.getSource());
				}))
				.then(literal("near").executes(c -> {
					config.despawnNear = !config.despawnNear;
					return despawnChanged(c.getSource());
				}))
				.then(aroundOptions()))
			// The sphere inside which nothing spawns (24 blocks).
			.then(literal("nospawn")
				.executes(c -> {
					config.noSpawn = !config.noSpawn;
					return despawnChanged(c.getSource());
				})
				.then(aroundOptions()))
			.then(literal("why").executes(c -> {
				SpawnCheckClient.explain(Minecraft.getInstance());
				return 1;
			}).then(argument("x", IntegerArgumentType.integer())
				.then(argument("y", IntegerArgumentType.integer())
					.then(argument("z", IntegerArgumentType.integer()).executes(c -> {
						SpawnCheckClient.explainAt(Minecraft.getInstance(), new BlockPos(IntegerArgumentType.getInteger(c, "x"),
							IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")));
						return 1;
					})))))
			.then(literal("fake")
				.executes(c -> sendFake(c.getSource(), new Payloads.FakeRequest(Payloads.FakeRequest.SPAWN, false, BlockPos.ZERO, "")))
				.then(literal("remove")
					.executes(c -> sendFake(c.getSource(), new Payloads.FakeRequest(Payloads.FakeRequest.REMOVE_ALL, false, BlockPos.ZERO, "")))
					.then(this.<String>argument("name", StringArgumentType.word())
						.suggests((context, builder) -> {
							var connection = Minecraft.getInstance().getConnection();
							if (connection != null) {
								String typed = builder.getRemainingLowerCase();
								for (var info : connection.getOnlinePlayers()) {
									String name = info.getProfile().name();
									if (name.startsWith(FAKE_PREFIX) && name.toLowerCase().startsWith(typed)) {
										builder.suggest(name);
									}
								}
							}
							return builder.buildFuture();
						})
						.executes(c -> sendFake(c.getSource(), new Payloads.FakeRequest(Payloads.FakeRequest.REMOVE_ONE, false, BlockPos.ZERO,
							StringArgumentType.getString(c, "name"))))))
				.then(argument("x", IntegerArgumentType.integer())
					.then(argument("y", IntegerArgumentType.integer())
						.then(argument("z", IntegerArgumentType.integer()).executes(c ->
							sendFake(c.getSource(), new Payloads.FakeRequest(Payloads.FakeRequest.SPAWN, true, new BlockPos(
								IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")), "")))))))
			.then(literal("at")
				.then(literal("player").executes(c -> {
					config.useCenter = false;
					return centerChanged(c.getSource(), "Scanning around you");
				}))
				.then(argument("x", IntegerArgumentType.integer())
					.then(argument("y", IntegerArgumentType.integer())
						.then(argument("z", IntegerArgumentType.integer()).executes(c -> {
							config.useCenter = true;
							config.cx = IntegerArgumentType.getInteger(c, "x");
							config.cy = IntegerArgumentType.getInteger(c, "y");
							config.cz = IntegerArgumentType.getInteger(c, "z");
							return centerChanged(c.getSource(), "Scanning around " + config.cx + " " + config.cy + " " + config.cz);
						})))))
			.then(literal("radius").then(argument("blocks", IntegerArgumentType.integer(1, SpawnCheckMod.MAX_RADIUS)).executes(c -> {
				setRadius(c.getSource(), IntegerArgumentType.getInteger(c, "blocks"));
				return 1;
			})))
			.then(literal("height").then(argument("blocks", IntegerArgumentType.integer(1, SpawnCheckMod.MAX_V_RADIUS)).executes(c -> {
				setHeight(c.getSource(), IntegerArgumentType.getInteger(c, "blocks"));
				return 1;
			})))
			// Both at once, so it's capped by the smaller of the two limits (the horizontal one).
			.then(literal("size").then(argument("blocks", IntegerArgumentType.integer(1, Math.min(SpawnCheckMod.MAX_RADIUS, SpawnCheckMod.MAX_V_RADIUS))).executes(c -> {
				int blocks = IntegerArgumentType.getInteger(c, "blocks");
				setRadius(c.getSource(), blocks);
				setHeight(c.getSource(), blocks);
				return 1;
			})))
			.then(literal("mode")
				.then(literal("any").executes(c -> {
					config.mode = Mode.ANY.ordinal();
					return modeChanged(c.getSource());
				}))
				.then(literal("mob").then(mobArgument(Mode.MOB)))
				.then(literal("only").then(mobArgument(Mode.ONLY))));
	}

	private RequiredArgumentBuilder<S, String> mobArgument(final Mode mode) {
		return this.<String>argument("mob", StringArgumentType.greedyString())
			.suggests((context, builder) -> {
				String typed = builder.getRemainingLowerCase();
				for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
					String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
					if (name.startsWith(typed)) {
						builder.suggest(name);
					}
				}
				return builder.buildFuture();
			})
			.executes(c -> {
				String text = StringArgumentType.getString(c, "mob").trim();
				if (ServerHandler.parseMob(text) == null) {
					feedback.error(c.getSource(), Component.literal("Unknown mob '" + text + "'"));
					return 0;
				}
				config.mode = mode.ordinal();
				config.mob = text.toLowerCase();
				return modeChanged(c.getSource());
			});
	}

	private int sendFake(final S source, final Payloads.FakeRequest request) {
		if (!SpawnCheckClient.platform.canSend(Payloads.FakeRequest.TYPE)) {
			feedback.error(source, Component.literal("This server doesn't have Spawn Check installed."));
			return 0;
		}
		SpawnCheckClient.platform.send(request);
		return 1;
	}

	/** {@code around you|fake|both}: whose position all the spheres are centred on. */
	private LiteralArgumentBuilder<S> aroundOptions() {
		LiteralArgumentBuilder<S> around = literal("around");
		for (Around option : Around.values()) {
			around.then(literal(option.name().toLowerCase()).executes(c -> {
				config.despawnAround = option.ordinal();
				return despawnChanged(c.getSource());
			}));
		}
		return around;
	}

	private int despawnChanged(final S source) {
		config.save();
		feedback.info(source, Component.literal("Spheres round " + SpawnCheckClient.aroundText() + ": no-spawn (" + SpawnAnalyzer.NO_SPAWN_DISTANCE + ") " + (config.noSpawn ? "on" : "off")
			+ ", no-despawn (" + SpawnCheckClient.nearRadius + ") " + (config.despawnNear ? "on" : "off")
			+ ", despawn (" + SpawnCheckClient.farRadius + ") " + (config.despawn ? "on" : "off")));
		return 1;
	}

	private void setRadius(final S source, final int blocks) {
		config.radius = blocks;
		config.save();
		feedback.info(source, Component.literal("Horizontal radius " + blocks));
	}

	private void setHeight(final S source, final int blocks) {
		config.height = blocks;
		config.save();
		feedback.info(source, Component.literal("Vertical radius " + blocks));
	}

	private int centerChanged(final S source, final String message) {
		config.enabled = true;
		config.save();
		SpawnCheckClient.rescan();
		feedback.info(source, Component.literal(message));
		return 1;
	}

	private int modeChanged(final S source) {
		config.enabled = true;
		config.save();
		SpawnCheckClient.rescan();
		feedback.info(source, Component.literal("Spawn mode: " + SpawnCheckClient.describeMode()));
		return 1;
	}
}
