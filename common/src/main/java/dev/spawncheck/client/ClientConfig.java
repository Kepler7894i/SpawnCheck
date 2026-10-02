package dev.spawncheck.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.spawncheck.SpawnCheckMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted overlay settings (config/spawncheck.json). */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Where this config is saved; not part of the saved settings. */
	private transient Path file;

	public boolean enabled = false;
	public boolean hud = true;
	/** Draws the distance beyond which mobs despawn at once. */
	public boolean despawn = false;
	/** Draws the closer distance inside which mobs never despawn (beyond it they can vanish at random). */
	public boolean despawnNear = false;
	/** Draws the distance inside which nothing spawns. */
	public boolean noSpawn = false;
	/** Whose position the spheres are centred on (see {@link Around}). */
	public int despawnAround = 0;
	/** 0 = any, 1 = mob, 2 = only (see {@link dev.spawncheck.analysis.Mode}). */
	public int mode = 1;
	public String mob = "creeper";
	public int radius = 128;
	public int height = 6;
	/** When true, scan around (cx, cy, cz) instead of the player. */
	public boolean useCenter = false;
	public int cx;
	public int cy;
	public int cz;

	/** Loads {@code spawncheck.json} from the loader's config folder (defaults if it is missing or unreadable). */
	public static ClientConfig load(final Path configDir) {
		Path file = configDir.resolve("spawncheck.json");
		ClientConfig config = null;
		try {
			if (Files.exists(file)) {
				config = GSON.fromJson(Files.readString(file), ClientConfig.class);
			}
		} catch (IOException | RuntimeException e) {
			SpawnCheckMod.LOGGER.warn("Could not read {}", file, e);
		}
		if (config == null) {
			config = new ClientConfig();
		}
		config.file = file;
		return config;
	}

	public void save() {
		try {
			Files.writeString(file, GSON.toJson(this));
		} catch (IOException e) {
			SpawnCheckMod.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
