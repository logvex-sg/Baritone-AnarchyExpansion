package dev.basehunt.bhclient.core.logic;

/**
 * Weighted confidence score for a suspected stash.
 *
 * <p>A single chest is not a base. The score combines several independent indicators - container counts,
 * container type, storage density, spawners, suspicious block arrangements - so an isolated chest scores low
 * and only a real concentration reaches the "confirmed" range. Each indicator contributes on its own, so no
 * single false positive can push a location over the threshold.
 */
public final class StashScorer {
    /** Raw observations from one chunk (or one merged cluster of chunks). */
    public record Evidence(
        int chests,
        int trappedChests,
        int barrels,
        int shulkerBoxes,
        int enderChests,
        int furnaces,
        int hoppers,
        int dispensers,
        int spawners,
        int beaconLike,
        int suspiciousPatterns
    ) {
        public int containerCount() {
            return chests + trappedChests + barrels + shulkerBoxes + enderChests;
        }

        public int totalStorage() {
            return containerCount() + furnaces + hoppers + dispensers;
        }
    }

    // Weights. Shulkers are worth the most because they are expensive and portable, so a pile of them is a
    // strong signal of a working base rather than a mine entrance.
    private static final int W_CHEST = 2;
    private static final int W_TRAPPED = 3;
    private static final int W_BARREL = 2;
    private static final int W_SHULKER = 8;
    private static final int W_ENDER_CHEST = 3;
    private static final int W_FURNACE = 1;
    private static final int W_HOPPER = 2;
    private static final int W_DISPENSER = 1;
    private static final int W_SPAWNER = 6;
    private static final int W_BEACON = 10;
    private static final int W_PATTERN = 4;

    // Bonuses for concentration rather than mere presence.
    private static final int DENSITY_BONUS_STEP = 4;
    private static final int DENSITY_BONUS_MAX = 24;
    private static final int DIVERSITY_BONUS_PER_TYPE = 3;
    private static final int DIVERSITY_BONUS_MAX = 15;

    private StashScorer() {
    }

    /** Total weighted score for the evidence. Always non-negative. */
    public static int score(Evidence e) {
        int base = e.chests() * W_CHEST
            + e.trappedChests() * W_TRAPPED
            + e.barrels() * W_BARREL
            + e.shulkerBoxes() * W_SHULKER
            + e.enderChests() * W_ENDER_CHEST
            + e.furnaces() * W_FURNACE
            + e.hoppers() * W_HOPPER
            + e.dispensers() * W_DISPENSER
            + e.spawners() * W_SPAWNER
            + e.beaconLike() * W_BEACON
            + e.suspiciousPatterns() * W_PATTERN;

        // Density bonus: many containers packed together are more than the sum of their parts.
        int containers = e.containerCount();
        int densityBonus = Math.min(DENSITY_BONUS_MAX, Math.max(0, containers - 2) * DENSITY_BONUS_STEP / 2);

        // Diversity bonus: a base has a mix of storage types, a lone chest does not.
        int types = 0;
        if (e.chests() > 0) types++;
        if (e.trappedChests() > 0) types++;
        if (e.barrels() > 0) types++;
        if (e.shulkerBoxes() > 0) types++;
        if (e.enderChests() > 0) types++;
        if (e.furnaces() > 0) types++;
        if (e.hoppers() > 0) types++;
        int diversityBonus = Math.min(DIVERSITY_BONUS_MAX, types * DIVERSITY_BONUS_PER_TYPE);

        return base + densityBonus + diversityBonus;
    }

    /**
     * Classifies a score. Thresholds are deliberately well separated so a small stash is not reported as a
     * major storage location.
     */
    public static StashClassification classify(int score, int containers) {
        if (containers <= 0 && score <= 0) return StashClassification.UNCONFIRMED;
        if (score >= 400 || containers >= 120) return StashClassification.MAJOR_STORAGE;
        if (score >= 180 || containers >= 60) return StashClassification.LARGE_STASH;
        if (score >= 80 || containers >= 24) return StashClassification.MEDIUM_STASH;
        if (score >= 24 || containers >= 8) return StashClassification.SMALL_STASH;
        if (score >= 8 || containers >= 3) return StashClassification.POSSIBLE_STASH;
        return StashClassification.UNCONFIRMED;
    }

    /** True when a chunk should be reported at all under the given minimum score. */
    public static boolean meetsThreshold(int score, int containers, int minScore, int minContainers) {
        return score >= minScore || containers >= minContainers;
    }
}
