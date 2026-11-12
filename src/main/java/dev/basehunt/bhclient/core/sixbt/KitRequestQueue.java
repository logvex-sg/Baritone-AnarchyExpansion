package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The queue of kit requests a 6b6t delivery bot has accepted but not yet served.
 *
 * <p>Public chat on 6b6t is busy, and the same player will often ask twice because they did not see the bot
 * reply. Serving both would teleport the bot back and forth, so requests are de-duplicated per requester and
 * rate-limited per player: a second request from someone already queued is dropped, and a player who was
 * just served has to wait before asking again.
 *
 * <p>The queue is pure and clock-injected, so "a duplicate is dropped", "the cooldown holds" and "the oldest
 * request is served first" are all covered by the headless suite.
 */
public final class KitRequestQueue {

    /** One accepted request. */
    public record Pending(String requester, String kit, long atMillis) {
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Long> lastServed = new LinkedHashMap<>();

    private final int capacity;
    private final long cooldownMillis;

    public KitRequestQueue(int capacity, long cooldownMillis) {
        this.capacity = Math.max(1, capacity);
        this.cooldownMillis = Math.max(0, cooldownMillis);
    }

    /** The reason a request was refused, or null when it was accepted. */
    public String offer(Pending request, long nowMillis) {
        if (request == null || request.requester() == null || request.requester().isBlank()) return "no requester";

        String key = request.requester().toLowerCase(java.util.Locale.ROOT);

        if (pending.containsKey(key)) return "already queued";
        if (pending.size() >= capacity) return "queue full";

        Long served = lastServed.get(key);
        if (served != null && nowMillis - served < cooldownMillis) {
            long wait = (cooldownMillis - (nowMillis - served)) / 1000;
            return "cooldown (" + wait + "s)";
        }

        pending.put(key, request);
        return null;
    }

    /** The oldest pending request, or null when the queue is empty. */
    public Pending peek() {
        for (Pending request : pending.values()) return request;
        return null;
    }

    /** Removes and returns the oldest pending request, or null when the queue is empty. */
    public Pending poll() {
        var iterator = pending.values().iterator();
        if (!iterator.hasNext()) return null;
        Pending request = iterator.next();
        iterator.remove();
        return request;
    }

    /** Records that a requester has been served, starting their cooldown. */
    public void served(String requester, long nowMillis) {
        if (requester == null) return;
        lastServed.put(requester.toLowerCase(java.util.Locale.ROOT), nowMillis);
    }

    public boolean isPending(String requester) {
        return requester != null && pending.containsKey(requester.toLowerCase(java.util.Locale.ROOT));
    }

    /** The 1-based position of a pending requester, or 0 when they are not queued. */
    public int positionOf(String requester) {
        if (requester == null) return 0;
        String key = requester.toLowerCase(java.util.Locale.ROOT);
        int position = 1;
        for (String pendingKey : pending.keySet()) {
            if (pendingKey.equals(key)) return position;
            position++;
        }
        return 0;
    }

    /** The reason a requester is refused without touching the queue: "cooldown" or "full", or null. */
    public String wouldRefuse(String requester, long nowMillis) {
        if (requester == null || requester.isBlank()) return "no requester";
        String key = requester.toLowerCase(java.util.Locale.ROOT);
        if (pending.containsKey(key)) return "already queued";
        if (pending.size() >= capacity) return "full";
        Long served = lastServed.get(key);
        if (served != null && nowMillis - served < cooldownMillis) return "cooldown";
        return null;
    }

    /** The seconds remaining on a requester's cooldown, or 0. */
    public long cooldownSecondsLeft(String requester, long nowMillis) {
        if (requester == null) return 0;
        Long served = lastServed.get(requester.toLowerCase(java.util.Locale.ROOT));
        if (served == null) return 0;
        long remaining = cooldownMillis - (nowMillis - served);
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public boolean remove(String requester) {
        return requester != null && pending.remove(requester.toLowerCase(java.util.Locale.ROOT)) != null;
    }

    public int size() {
        return pending.size();
    }

    public int capacity() {
        return capacity;
    }

    public long cooldownMillis() {
        return cooldownMillis;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public void clear() {
        pending.clear();
    }

    public List<Pending> all() {
        return new ArrayList<>(pending.values());
    }

    /** A one-line status for the HUD and terminal. */
    public String statusLine() {
        if (pending.isEmpty()) return "no pending requests";
        Pending next = peek();
        return "%d pending, next %s (%s)".formatted(pending.size(), next.requester(), next.kit());
    }
}
