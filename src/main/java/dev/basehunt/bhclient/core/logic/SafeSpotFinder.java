package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks nearby blocks by how safe they are to stand on when you suddenly need to stop and think.
 *
 * <p>When a threat appears or a flight goes wrong the worst place to be is a lit, exposed block in the open
 * with lava behind you. A person would duck behind cover; an automated client that just freezes in place
 * stands out and dies. This class takes a small set of candidate spots the caller sampled around the player
 * and scores each one, so the choice of where to retreat is a piece of pure, testable logic rather than a
 * scatter of comparisons inside a tick handler.
 *
 * <p>It is deliberately conservative: it will always prefer a spot that is enclosed over one that is merely
 * far, and it refuses to recommend a spot next to lava or a drop no matter how good it otherwise looks.
 */
public final class SafeSpotFinder {
    /**
     * One candidate block, described by what the caller saw around it.
     *
     * @param coveredBlocks how many of the four horizontal neighbours block movement (0-4)
     * @param overheadSolid true when there is a solid block above, so the spot is not open to the sky
     * @param lavaAdjacent  a lava block touches this position
     * @param fireAdjacent  fire or a magma block touches this position
     * @param waterAdjacent water touches this position (a small plus: mobs path poorly, you do not burn)
     * @param dropOff       how many blocks it is to the ground below (0 when the floor is right there)
     * @param lightLevel    block light at the spot, 0-15
     */
    public record Spot(int x, int y, int z, int coveredBlocks, boolean overheadSolid,
                       boolean lavaAdjacent, boolean fireAdjacent, boolean waterAdjacent,
                       int dropOff, int lightLevel) {
        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }
    }

    /** A scored spot. */
    public record Ranked(Spot spot, int score, String reason) {
    }

    private SafeSpotFinder() {
    }

    /**
     * Scores a spot on a 0-100 scale. Higher is safer.
     *
     * <p>Cover is worth the most, then overhead roof, then darkness, then water; lava and fire are
     * disqualifying rather than merely expensive, and a drop of more than a couple of blocks subtracts
     * heavily.
     */
    public static int score(Spot spot) {
        if (spot.lavaAdjacent()) return 0;
        if (spot.fireAdjacent()) return 0;

        int score = 30;
        score += Math.min(4, Math.max(0, spot.coveredBlocks())) * 8;
        if (spot.overheadSolid()) score += 12;
        if (spot.waterAdjacent()) score += 4;

        // Darker is safer from mob visibility and reads as sheltered; brighter spots are exposed.
        score += (15 - Math.max(0, Math.min(15, spot.lightLevel())));

        // A drop is a fall risk: up to 3 blocks is tolerable, beyond that it is a hazard.
        int drop = Math.max(0, spot.dropOff());
        if (drop > 3) score -= (drop - 3) * 6;

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Ranks the candidates best-first. Unsafe spots (score 0 from lava/fire) are dropped so the caller is
     * never told to retreat into a hazard; a list with no viable spot is returned empty rather than filled
     * with bad options.
     */
    public static List<Ranked> rank(List<Spot> candidates) {
        List<Ranked> ranked = new ArrayList<>();
        if (candidates == null) return ranked;

        for (Spot spot : candidates) {
            int score = score(spot);
            if (score <= 0) continue;
            ranked.add(new Ranked(spot, score, reason(spot, score)));
        }

        ranked.sort(Comparator.comparingInt(Ranked::score).reversed());
        return ranked;
    }

    /** The best spot, or null when none is worth using. */
    public static Ranked best(List<Spot> candidates) {
        List<Ranked> ranked = rank(candidates);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private static String reason(Spot spot, int score) {
        if (spot.coveredBlocks() >= 3) return "enclosed";
        if (spot.overheadSolid()) return "roofed";
        if (spot.waterAdjacent()) return "by water";
        if (score >= 45) return "sheltered";
        return "open";
    }
}
