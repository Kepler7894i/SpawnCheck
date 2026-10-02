package dev.spawncheck;

import dev.spawncheck.analysis.SpawnAnalyzer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-independent entry point: each loader's mod class calls {@link #init} before registering its own events and payloads. */
public final class SpawnCheckMod {
	public static final String MOD_ID = "spawncheck";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final int MAX_CELLS = SpawnAnalyzer.MAX_CELLS;
	/** Spawning only happens within 128 blocks of a player, so a wider scan would never show anything. */
	public static final int MAX_RADIUS = 128;
	/** Large enough to cover the tallest possible dimension (4064 blocks); the scan clamps to the real world height. */
	public static final int MAX_V_RADIUS = 2032;

	private static Platform platform;

	private SpawnCheckMod() {
	}

	public static void init(final Platform loaderPlatform) {
		platform = loaderPlatform;
	}

	public static Platform platform() {
		return platform;
	}

	public static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
