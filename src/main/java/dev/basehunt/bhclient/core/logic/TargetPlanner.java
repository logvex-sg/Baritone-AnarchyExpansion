package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks candidate destinations so the route visits the most promising one next.
 *
 * <p>A base hunter has to trade off several things at once: a high-confidence stash is worth a detour, but
 * only if it is not so far that the elytra is spent getting there; a location seen before is worth less than
 * a fresh one. The score is a weighted sum of those factors rather than a single rule, so the user can tune
 * how aggressive the client is with {@code aggression}.
 */
public final class TargetPlanner {

    /** A place the client could visit. */
    public record Candidate(String label, int x, int z, String dimension, int confidence,
                            boolean previouslyDiscovered, long lastSeen, String kind) {
    }

    /** A scored destination, ordered by score. */
    public record Scored(Candidate candidate, double score) {
    }

    /** Weights controlling how targets are prioritised. */
    public record Weights(double confidenceWeight, double distancePenalty, double revisitPenalty,
                          double distanceBias, double kindWeight) {
        public static Weights defaults() {
            return new Weights(1.0, 0.02, 0.5, 1.0, 1.0);
        }

        /** An aggressive set: confidence matters more, distance matters less. */
        public static Weights aggressive() {
            return new Weights(1.6, 0.01, 0.3, 1.0, 1.2);
        }

        /** A cautious set: stay close to home and avoid long detours. */
        public static Weights cautious() {
            return new Weights(0.8, 0.05, 0.8, 1.0, 0.8);
        }
    }

    private TargetPlanner() {
    }

    /**
     * Scores every candidate and returns them best-first.
     *
     * @param fromX      the player's current X
     * @param fromZ      the player's current Z
     * @param dimension  the player's current dimension; candidates elsewhere are skipped
     */
    public static List<Scored> rank(List<Candidate> candidates, int fromX, int fromZ, String dimension, Weights weights) {
        List<Scored> scored = new ArrayList<>();

        for (Candidate candidate : candidates) {
            if (dimension != null && candidate.dimension() != null && !candidate.dimension().equals(dimension)) continue;
            scored.add(new Scored(candidate, score(candidate, fromX, fromZ, weights)));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored;
    }

    /** Score for one candidate: higher is better. */
    public static double score(Candidate candidate, int fromX, int fromZ, Weights weights) {
        double distance = Math.hypot(candidate.x() - fromX, candidate.z() - fromZ);

        double value = candidate.confidence() * weights.confidenceWeight();
        value += weights.kindWeight() * kindBonus(candidate.kind());

        // Distance reduces the score, but a small bias term keeps a very distant, very rich target attractive.
        value -= distance * weights.distancePenalty();
        value += Math.log10(Math.max(1, candidate.confidence())) * weights.distanceBias();

        if (candidate.previouslyDiscovered()) value -= weights.revisitPenalty() * candidate.confidence();

        return value;
    }

    private static double kindBonus(String kind) {
        if (kind == null) return 0;
        return switch (kind.toLowerCase()) {
            case "major storage location" -> 40;
            case "large stash" -> 25;
            case "medium stash" -> 12;
            case "small stash" -> 5;
            case "interesting structure" -> 8;
            default -> 0;
        };
    }

    /** The single best destination, or null when there is nothing to visit. */
    public static Candidate best(List<Candidate> candidates, int fromX, int fromZ, String dimension, Weights weights) {
        List<Scored> ranked = rank(candidates, fromX, fromZ, dimension, weights);
        return ranked.isEmpty() ? null : ranked.get(0).candidate();
    }
}
