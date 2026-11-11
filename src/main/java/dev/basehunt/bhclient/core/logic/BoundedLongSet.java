package dev.basehunt.bhclient.core.logic;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * A set of packed chunk keys with a hard size cap and oldest-first eviction.
 *
 * <p>Chunk scanners run for hours and touch hundreds of thousands of chunks. An unbounded {@code HashSet} of
 * keys would grow until the JVM runs out of memory, and a long flight is exactly when that is least
 * welcome. This set keeps a fixed ceiling: once it is full, the key added first is dropped to make room, so
 * the most recently seen chunks are the ones retained - which is what a scanner wants, since it re-scans the
 * area around the player.
 *
 * <p>All methods are synchronized so a scanner that evaluates chunks off the client thread cannot corrupt the
 * internal order queue. An optional eviction callback lets a caller keep a parallel map (for example, the
 * "found at" timestamps) in step with the set instead of pruning it separately.
 *
 * <p>It is pure Java with no Minecraft types, so the eviction rule is asserted by the headless suite.
 */
public final class BoundedLongSet {
    private final int maxSize;
    private final LongConsumer onEvict;
    private final Set<Long> values = new HashSet<>();
    private final Queue<Long> order = new ArrayDeque<>();

    public BoundedLongSet(int maxSize) {
        this(maxSize, null);
    }

    /**
     * @param maxSize the maximum number of keys retained
     * @param onEvict called with each key dropped by eviction or removal; may be null
     */
    public BoundedLongSet(int maxSize, LongConsumer onEvict) {
        this.maxSize = Math.max(1, maxSize);
        this.onEvict = onEvict;
    }

    /** Adds a key. Returns false when it was already present. */
    public synchronized boolean add(long key) {
        if (!values.add(key)) return false;
        order.add(key);
        evict();
        return true;
    }

    public synchronized boolean contains(long key) {
        return values.contains(key);
    }

    /** Removes a key, invoking the eviction callback when it was present. */
    public synchronized boolean remove(long key) {
        if (!values.remove(key)) return false;
        if (onEvict != null) onEvict.accept(key);
        return true;
    }

    public synchronized int size() {
        return values.size();
    }

    /** A point-in-time copy of the retained keys. */
    public synchronized Set<Long> snapshot() {
        return new HashSet<>(values);
    }

    public synchronized void clear() {
        values.clear();
        order.clear();
    }

    private void evict() {
        while (order.size() > maxSize) {
            Long oldest = order.poll();
            if (oldest == null) break;
            if (values.remove(oldest) && onEvict != null) onEvict.accept(oldest);
        }
    }
}
