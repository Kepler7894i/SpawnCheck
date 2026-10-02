package dev.spawncheck.analysis;

import net.minecraft.core.BlockPos;

/** One highlighted spot. {@code status} is one of the STATUS_* constants. */
public record Cell(BlockPos pos, byte status) {
	public static final byte STATUS_NO = 0;
	public static final byte STATUS_YES = 1;
	/** Target mob can spawn, but so can other mobs (ONLY mode). */
	public static final byte STATUS_SHARED = 2;
}
