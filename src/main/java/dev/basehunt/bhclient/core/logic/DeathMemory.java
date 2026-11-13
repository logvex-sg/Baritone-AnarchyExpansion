package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Remembers where you died and what you were carrying, so a death is recoverable instead of a loss.
 *
 * <p>On an anarchy server the whole point of a stash run is the loot you are carrying, and the moment it
 * matters most is the moment you die - often hundreds of blocks from spawn, in a place you did not mark. The
 * client knows exactly where that was; this class keeps that fact, bounds the list so a long session cannot
 * grow without limit, and answers the two questions that matter: where is my nearest un-recovered death, and
 * how much am I carrying that I would rather not lose.
 *
 * <p>The logic is pure (positions, timestamps and strings only), so the eviction rule, the nearest-death
 * search and the recovery marking are covered headlessly rather than only being felt on the next death.
 */
public final class DeathMemory {
    /** One remembered death. */
    public record DeathRecord(
        long id,
        String server,
        String dimension,
        int x,
        int y,
        int z,
        long timestamp,
        String cause,
        int carriedItems,
        boolean recovered
    ) {
        /** Straight-line horizontal distance from a position, in blocks. */
        public double distanceTo(int px, int pz) {
            double dx = x - px;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }

        public DeathRecord asRecovered() {
            return new DeathRecord(id, server, dimension, x, y, z, timestamp, cause, carriedItems, true);
        }
    }

    private final List<DeathRecord> records = new ArrayList<>();
    private int cap = 64;
    private long nextId = 1;

    public DeathMemory() {
    }

    public DeathMemory(int cap) {
        setCap(cap);
    }

    /** Sets the maximum number of records kept. Lowering it evicts the oldest immediately. */
    public void setCap(int cap) {
        this.cap = Math.max(1, cap);
        evict();
    }

    public int cap() {
        return cap;
    }

    /**
     * Records a death, assigning it an id. The oldest record is evicted when the cap is exceeded, so the
     * newest deaths - the ones you might still recover - are always the ones kept.
     *
     * @return the stored record, with its assigned id
     */
    public DeathRecord add(String server, String dimension, int x, int y, int z,
                           String cause, int carriedItems, long timestamp) {
        DeathRecord record = new DeathRecord(nextId++, server, dimension, x, y, z,
            timestamp, cause == null ? "" : cause, Math.max(0, carriedItems), false);
        records.add(record);
        evict();
        return record;
    }

    private void evict() {
        while (records.size() > cap) records.remove(0);
    }

    public List<DeathRecord> all() {
        return List.copyOf(records);
    }

    public List<DeathRecord> forServer(String server) {
        List<DeathRecord> result = new ArrayList<>();
        for (DeathRecord record : records) {
            if (record.server().equals(server)) result.add(record);
        }
        return result;
    }

    public List<DeathRecord> pending() {
        List<DeathRecord> result = new ArrayList<>();
        for (DeathRecord record : records) {
            if (!record.recovered()) result.add(record);
        }
        return result;
    }

    public int size() {
        return records.size();
    }

    public int pendingCount() {
        return pending().size();
    }

    /**
     * The nearest un-recovered death to a position in a given dimension, if any.
     *
     * <p>Only same-dimension deaths are considered: a death in the Nether is not reachable from the
     * Overworld, so reporting it as "nearest" would send you to a coordinate that means nothing.
     */
    public Optional<DeathRecord> nearestPending(String server, String dimension, int x, int z) {
        return records.stream()
            .filter(record -> !record.recovered())
            .filter(record -> record.server().equals(server))
            .filter(record -> record.dimension().equals(dimension))
            .min(Comparator.comparingDouble(record -> record.distanceTo(x, z)));
    }

    /** Marks the record with the given id recovered. Returns true when one was found. */
    public boolean markRecovered(long id) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id() == id) {
                records.set(i, records.get(i).asRecovered());
                return true;
            }
        }
        return false;
    }

    public int removeWhere(java.util.function.Predicate<DeathRecord> predicate) {
        int before = records.size();
        records.removeIf(predicate);
        return before - records.size();
    }

    public void clear() {
        records.clear();
    }

    /** Replaces the whole list, e.g. when loading from disk. Ids are preserved. */
    public void replaceAll(List<DeathRecord> loaded) {
        records.clear();
        records.addAll(loaded);
        long maxId = 0;
        for (DeathRecord record : records) maxId = Math.max(maxId, record.id());
        nextId = maxId + 1;
        evict();
    }

    /**
     * A one-line summary of the nearest pending death for the HUD, or empty when there is nothing to recover.
     */
    public String nearestSummary(String server, String dimension, int x, int z) {
        return nearestPending(server, dimension, x, z)
            .map(record -> "%s %s (%.0fm)".formatted(record.coords(), record.dimension(),
                record.distanceTo(x, z)))
            .orElse("");
    }

    /** A human label for a death, used in chat and the GUI. */
    public static String describe(DeathRecord record) {
        return "#%d %s %s%s".formatted(record.id(), record.coords(), record.dimension(),
            record.recovered() ? " (recovered)" : "");
    }

    /** Normalises a dimension name so "minecraft:overworld" and "overworld" compare equal. */
    public static String normalizeDimension(String dimension) {
        if (dimension == null) return "unknown";
        String trimmed = dimension.toLowerCase(Locale.ROOT);
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(colon + 1);
    }
}
