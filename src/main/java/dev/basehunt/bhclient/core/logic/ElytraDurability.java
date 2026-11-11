package dev.basehunt.bhclient.core.logic;

/**
 * Pure durability arithmetic for elytra (and any other damageable gear).
 *
 * <p>Kept free of Minecraft types so the replacement and viability rules can be verified headlessly: these
 * numbers decide whether a flight starts, continues or is abandoned, which is exactly the logic that must
 * not silently drift.
 */
public final class ElytraDurability {
    /** Below this many remaining points an elytra is treated as unusable for a new journey. */
    public static final int DEFAULT_MINIMUM = 16;

    private ElytraDurability() {
    }

    public static int remaining(int maxDamage, int damage) {
        if (maxDamage <= 0) return 0;
        return Math.max(0, maxDamage - damage);
    }

    /** Remaining durability as a percentage in [0, 100]. Returns 0 for a non-damageable stack. */
    public static double percent(int remaining, int maxDamage) {
        if (maxDamage <= 0) return 0;
        return Math.max(0, Math.min(100, remaining * 100.0 / maxDamage));
    }

    /** True when the worn elytra has dropped to the point where a spare should be swapped in. */
    public static boolean shouldReplace(int remaining, int threshold) {
        return threshold > 0 && remaining <= threshold;
    }

    /**
     * True when an elytra is good enough to start or continue a journey. A missing elytra (remaining &lt; 0)
     * and one below the minimum both fail.
     */
    public static boolean isViable(int remaining, int minimum) {
        return remaining >= 0 && remaining >= Math.max(0, minimum);
    }

    /**
     * Index of the healthiest candidate at or above {@code minimum}, or -1 when none qualifies.
     *
     * @param remainingBySlot remaining durability per inventory slot; negative entries are ignored
     */
    public static int bestSlot(int[] remainingBySlot, int minimum) {
        int best = -1;
        int bestRemaining = -1;

        for (int slot = 0; slot < remainingBySlot.length; slot++) {
            int remaining = remainingBySlot[slot];
            if (remaining < 0) continue;
            if (remaining < minimum) continue;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                best = slot;
            }
        }

        return best;
    }

    /**
     * True when there is enough total durability across the worn elytra and the spares to finish a journey of
     * the given length. Roughly one durability point is consumed per 100 blocks flown in Bounce mode, so the
     * estimate is deliberately conservative.
     */
    public static boolean canCompleteJourney(int wornRemaining, int[] spareRemaining, int journeyBlocks, int minimum) {
        int total = Math.max(0, wornRemaining);
        for (int remaining : spareRemaining) if (remaining > 0) total += remaining;

        int required = journeyBlocks / 100 + Math.max(0, minimum);
        return total >= required;
    }
}
