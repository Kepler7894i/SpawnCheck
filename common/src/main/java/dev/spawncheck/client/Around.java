package dev.spawncheck.client;

/** Whose position the despawn spheres are centred on. */
public enum Around {
	YOU,
	/** The stand-in players placed with /spawncheck fake. */
	FAKE,
	BOTH;

	public static Around byId(int id) {
		Around[] values = values();
		return values[Math.floorMod(id, values.length)];
	}

	public boolean includesYou() {
		return this != FAKE;
	}

	public boolean includesFake() {
		return this != YOU;
	}
}
