package dev.spawncheck.analysis;

/** Mob-cap numbers for one category. {@code local} is for the requesting player. */
public record CapInfo(String category, int global, int globalMax, int local, int localMax) {
}
