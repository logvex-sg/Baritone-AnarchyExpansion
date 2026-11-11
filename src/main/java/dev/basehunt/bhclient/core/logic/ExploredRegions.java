package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which chunks have been explored, and which are still worth visiting.
 *
 * <p>Two failure modes this guards against: re-flying over the same empty territory (wasted elytra), and an
 * unbounded record of visited chunks slowly eating memory over a long session. Visited chunks are therefore
 * stored as a bounded set of packed longs with oldest-first eviction, and the "requires another look" list is
 * capped separately.
 *
 * <p>Packed keys use the same layout as {@link net.minecraft.util.math.ChunkPos#toLong} (x low, z high) but
 * the arithmetic is duplicated here so the class has no Minecraft dependency and can be tested headlessly.
 */
public final class ExploredRegions {
    private static final int DEFAULT_LIMIT = 250_000;

    private final int limit;
    private final Set<Long> visited = new HashSet<>();
    private final List<Long> visitOrder = new ArrayList<>();
    private final Set<Long> requiresReview = new HashSet<>();

    public ExploredRegions() {
        this(DEFAULT_LIMIT);
    }

    public ExploredRegions(int limit) {
        this.limit = Math.max(1024, limit);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >> 32);
    }

    public boolean wasExplored(int chunkX, int chunkZ) {
        return visited.contains(key(chunkX, chunkZ));
    }

    /** Marks a chunk explored. Evicts the oldest entries once the cap is reached. */
    public void markExplored(int chunkX, int chunkZ) {
        long key = key(chunkX, chunkZ);
        if (!visited.add(key)) return;

        visitOrder.add(key);
        while (visitOrder.size() > limit) {
            long evicted = visitOrder.remove(0);
            visited.remove(evicted);
        }
    }

    /** Flags a chunk as needing another inspection (e.g. an unresolved stash hint). */
    public void markRequiresReview(int chunkX, int chunkZ) {
        if (requiresReview.size() >= limit) {
            // Drop an arbitrary entry rather than growing without bound.
            var it = requiresReview.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        requiresReview.add(key(chunkX, chunkZ));
    }

    public void clearReview(int chunkX, int chunkZ) {
        requiresReview.remove(key(chunkX, chunkZ));
    }

    public boolean needsReview(int chunkX, int chunkZ) {
        return requiresReview.contains(key(chunkX, chunkZ));
    }

    public int exploredCount() {
        return visited.size();
    }

    public int reviewCount() {
        return requiresReview.size();
    }

    public Set<Long> exploredKeys() {
        return java.util.Collections.unmodifiableSet(visited);
    }

    public Set<Long> reviewKeys() {
        return java.util.Collections.unmodifiableSet(requiresReview);
    }

    public void clear() {
        visited.clear();
        visitOrder.clear();
        requiresReview.clear();
    }

    /**
     * Fraction of chunks within {@code radiusChunks} of the given centre that have been explored, in [0, 1].
     * Used to decide whether a region is "done" and exploration should expand.
     */
    public double coverageAround(int centreChunkX, int centreChunkZ, int radiusChunks) {
        int radius = Math.max(1, radiusChunks);
        int total = 0;
        int seen = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                total++;
                if (visited.contains(key(centreChunkX + dx, centreChunkZ + dz))) seen++;
            }
        }

        return total == 0 ? 0 : seen / (double) total;
    }
}
