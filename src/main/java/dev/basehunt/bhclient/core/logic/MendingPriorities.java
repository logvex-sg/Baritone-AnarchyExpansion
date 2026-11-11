package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which damaged Mending item to repair next, and whether repairing is worthwhile at all.
 *
 * <p>XP is scarce on anarchy servers, so the rule is: only repair when an XP source is actually available,
 * and repair the item that is most damaged first. An item that is already whole is never selected, which is
 * what stops available XP being wasted on equipment that does not need it.
 */
public final class MendingPriorities {
    /** A candidate piece of Mending equipment. */
    public record Candidate(String name, int slot, int remaining, int maxDamage, boolean equipped, int priorityWeight) {
        public int missing() {
            return Math.max(0, maxDamage - remaining);
        }

        public double damagedFraction() {
            if (maxDamage <= 0) return 0;
            return missing() / (double) maxDamage;
        }

        public boolean needsRepair() {
            return missing() > 0;
        }
    }

    private MendingPriorities() {
    }

    /**
     * Ranks candidates worst-first: most missing durability, then the configured weight, then equipped items
     * ahead of stored ones (an equipped elytra matters more than a spare chestplate).
     */
    public static List<Candidate> rank(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.needsRepair()) result.add(candidate);
        }

        result.sort(Comparator
            .comparingInt(Candidate::missing).reversed()
            .thenComparing(Comparator.comparingInt(Candidate::priorityWeight).reversed())
            .thenComparing(c -> c.equipped() ? 0 : 1));

        return result;
    }

    /**
     * True when it is worth starting a repair cycle.
     *
     * @param xpAvailable whether a legitimate XP source (orbs nearby, an XP farm, bottles) is present
     * @param worstMissing missing durability of the most damaged candidate
     * @param minMissing   do not bother repairing less damage than this
     */
    public static boolean shouldRepair(boolean xpAvailable, int worstMissing, int minMissing) {
        return xpAvailable && worstMissing >= Math.max(1, minMissing);
    }

    /** Index of the highest priority candidate, or -1 when nothing needs repair. */
    public static int selectSlot(List<Candidate> candidates) {
        List<Candidate> ranked = rank(candidates);
        return ranked.isEmpty() ? -1 : ranked.get(0).slot();
    }
}
