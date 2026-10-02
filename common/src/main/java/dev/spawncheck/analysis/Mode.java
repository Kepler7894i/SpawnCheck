package dev.spawncheck.analysis;

/** What the overlay is searching for. */
public enum Mode {
	/** Green where at least one mob can spawn right now. */
	ANY,
	/** Green where the chosen mob can spawn, red where it can't. */
	MOB,
	/** Green only where the chosen mob is the sole mob that can spawn; yellow if it shares the spot. */
	ONLY;

	public static Mode byId(int id) {
		Mode[] values = values();
		return values[Math.floorMod(id, values.length)];
	}

	public Mode next() {
		return byId(ordinal() + 1);
	}
}
