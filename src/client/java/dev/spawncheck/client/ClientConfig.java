package dev.spawncheck.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.spawncheck.SpawnCheckMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Persisted overlay settings (config/spawncheck.json). */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spawncheck.json");

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

	public static ClientConfig load() {
		try {
			if (Files.exists(FILE)) {
				ClientConfig loaded = GSON.fromJson(Files.readString(FILE), ClientConfig.class);
				if (loaded != null) {
					return loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			SpawnCheckMod.LOGGER.warn("Could not read {}", FILE, e);
		}
		return new ClientConfig();
	}

	public void save() {
		try {
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			SpawnCheckMod.LOGGER.warn("Could not write {}", FILE, e);
		}
	}
}
