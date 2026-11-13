package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a ranked, bounded list of "things worth going to do" on an anarchy server.
 *
 * <p>A stash run is rarely one errand. On the way home you notice a dropped shulker, you remember the base
 * of the player who logged out nearby, and there is a half-looted stash you meant to come back to. A person
 * keeps that list in their head and works it in a sensible order; an automated client that only ever serves
 * the single task it was handed leaves loot on the ground. This class is that list, made explicit.
 *
 * <p>It is pure: objectives carry only a kind, a position and a timestamp, and the ranking is a function of
 * those plus where the player is now. Priorities fold in what the objective is worth, how far away it is and
 * how stale it is, so a fresh valuable objective near you always beats a stale one across the map. The queue
 * is bounded and de-duplicated so a busy area cannot grow it without limit or fill it with the same spot
 * twice.
 */
public final class ObjectivePlanner {
    /** What kind of errand this is; the base worth of each is fixed here, not by the caller. */
    public enum Kind {
        /** Loot dropped by a death, an explosion or a broken chest. */
        DROP_PICKUP(90),
        /** The place a tracked player was last seen logging out - raid it before they return. */
        LOGOUT_SPOT(80),
        /** A stash worth another look (upgraded, or left only partly emptied). */
        STASH_REVISIT(60),
        /** A chunk region the explorer has flagged for review. */
        REVIEW_REGION(40),
        /** Anything the caller wants to queue by hand. */
        CUSTOM(50);

        public final int baseWorth;

        Kind(int baseWorth) {
            this.baseWorth = baseWorth;
        }
    }

    /**
     * One errand.
     *
     * @param id       stable identifier, so the same objective can be completed or refreshed
     * @param kind     what kind of errand it is
     * @param label    a short human description
     * @param x,y,z    where to go
     * @param created  when it was queued, in milliseconds
     */
    public record Objective(long id, Kind kind, String label, int x, int y, int z, long created) {
        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }

        public double distanceTo(int px, int py, int pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** A scored objective. */
    public record Ranked(Objective objective, double priority) {
    }

    private final List<Objective> objectives = new ArrayList<>();
    private int cap = 64;
    private long nextId = 1;

    public ObjectivePlanner() {
    }

    public ObjectivePlanner(int cap) {
        setCap(cap);
    }

    public void setCap(int cap) {
        this.cap = Math.max(1, cap);
        evict();
    }

    public int size() {
        return objectives.size();
    }

    public List<Objective> all() {
        return List.copyOf(objectives);
    }

    /** Replaces the whole list, e.g. when loading from disk. */
    public void replaceAll(List<Objective> loaded) {
        objectives.clear();
        long maxId = 0;
        if (loaded != null) {
            for (Objective objective : loaded) {
                if (objective == null) continue;
                objectives.add(objective);
                maxId = Math.max(maxId, objective.id());
            }
        }
        nextId = maxId + 1;
        evict();
    }

    public void clear() {
        objectives.clear();
    }

    /**
     * Adds an objective, unless an equivalent one already exists.
     *
     * <p>Equivalent means the same kind within {@code mergeRadius} blocks: queueing a second "loot this
     * death spot" a block away from the first would just make the list lie about how much there is to do.
     * When one does match, the existing entry is refreshed (moved to the new position and timestamped now)
     * and returned rather than a duplicate being added.
     */
    public Objective add(Kind kind, String label, int x, int y, int z, long now, int mergeRadius) {
        Objective existing = findNear(kind, x, y, z, mergeRadius);
        if (existing != null) {
            Objective refreshed = new Objective(existing.id(), kind, label, x, y, z, now);
            objectives.set(objectives.indexOf(existing), refreshed);
            return refreshed;
        }

        Objective objective = new Objective(nextId++, kind, label, x, y, z, now);
        objectives.add(objective);
        evict();
        return objective;
    }

    /** The first objective of this kind within {@code radius} of the position, or null. */
    public Objective findNear(Kind kind, int x, int y, int z, int radius) {
        double limit = Math.max(0, radius) * Math.max(0, radius);
        for (Objective objective : objectives) {
            if (objective.kind() != kind) continue;
            double dx = objective.x() - x;
            double dz = objective.z() - z;
            if (dx * dx + dz * dz <= limit) return objective;
        }
        return null;
    }

    public boolean complete(long id) {
        return objectives.removeIf(objective -> objective.id() == id);
    }

    public int removeWhere(java.util.function.Predicate<Objective> predicate) {
        int before = objectives.size();
        objectives.removeIf(predicate);
        return before - objectives.size();
    }

    public Objective get(long id) {
        for (Objective objective : objectives) if (objective.id() == id) return objective;
        return null;
    }

    /**
     * Ranks every objective against the player's position, best first.
     *
     * <p>Priority is the kind's worth, minus a distance cost, minus a staleness cost that saturates: an
     * objective queued five minutes ago is not much worse than a fresh one, but one queued an hour ago has
     * clearly gone cold. The formula is deliberately simple and monotonic in each input, so it is easy to
     * reason about and to test.
     */
    public List<Ranked> ranked(int px, int py, int pz, long now) {
        List<Ranked> result = new ArrayList<>();
        for (Objective objective : objectives) {
            result.add(new Ranked(objective, priority(objective, px, py, pz, now)));
        }
        result.sort(Comparator.comparingDouble(Ranked::priority).reversed());
        return result;
    }

    /** The best objective to pursue now, or null when the list is empty. */
    public Ranked next(int px, int py, int pz, long now) {
        List<Ranked> ranked = ranked(px, py, pz, now);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** Scores one objective: worth, less distance, less a saturating staleness penalty. */
    public static double priority(Objective objective, int px, int py, int pz, long now) {
        double worth = objective.kind().baseWorth;
        double distance = objective.distanceTo(px, py, pz);

        // Distance costs 1 point per 32 blocks, so a 128-block trip gives up 4 of its worth - enough to
        // prefer the nearer of two equal objectives without letting distance bury a valuable one entirely.
        double distanceCost = distance / 32.0;

        double ageMinutes = Math.max(0.0, (now - objective.created()) / 60000.0);
        // Staleness never costs more than 20: an old objective is deprioritised, not forgotten.
        double staleness = Math.min(20.0, ageMinutes / 3.0);

        return worth - distanceCost - staleness;
    }

    private void evict() {
        // Drop the lowest-priority entries when over capacity. Ranking against the origin keeps eviction
        // independent of where the player happens to be when the list overflows.
        while (objectives.size() > cap) {
            Objective worst = null;
            double worstPriority = Double.MAX_VALUE;
            for (Objective objective : objectives) {
                double p = priority(objective, 0, 0, 0, objective.created());
                if (p < worstPriority) {
                    worstPriority = p;
                    worst = objective;
                }
            }
            if (worst == null) break;
            objectives.remove(worst);
        }
    }
}
