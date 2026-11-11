package dev.basehunt.bhclient.core.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kits the client knows about and how long until each may be claimed again.
 *
 * <p>Because a server's kit cooldown is invisible to the client, this tracks the wall-clock time since the
 * addon last saw a claim succeed and refuses an early retry. A refusal is not a failure: it is the client
 * doing the sensible thing and not spamming a command the server will reject anyway.
 *
 * <p>The clock is injected as a long millisecond timestamp rather than read from the system, so the cooldown
 * arithmetic - including "never claimed", "still on cooldown" and "cooldown elapsed" - is testable.
 */
public final class KitRegistry {
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<String, Long> lastClaim = new LinkedHashMap<>();
    private final Map<String, Integer> claimCount = new LinkedHashMap<>();

    /** Registers or replaces a kit. */
    public void register(Kit kit) {
        if (kit == null) return;
        kits.put(kit.key(), kit);
    }

    public void registerAll(List<Kit> list) {
        if (list == null) return;
        for (Kit kit : list) register(kit);
    }

    public Kit find(String name) {
        if (name == null) return null;
        return kits.get(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public List<Kit> all() {
        return new ArrayList<>(kits.values());
    }

    public void clear() {
        kits.clear();
        lastClaim.clear();
        claimCount.clear();
    }

    /** Records that a claim for this kit succeeded at {@code nowMillis}. */
    public void recordClaim(String name, long nowMillis) {
        Kit kit = find(name);
        String key = kit == null ? name == null ? "" : name.toLowerCase(java.util.Locale.ROOT) : kit.key();
        lastClaim.put(key, nowMillis);
        claimCount.merge(key, 1, Integer::sum);
    }

    /** Records that a claim was attempted but failed; the cooldown is not reset. */
    public void recordFailure(String name) {
        // Deliberately no-op on the cooldown: a failed claim does not start the timer, so a corrected retry can
        // be immediate. Kept as a named method so the intent is explicit at the call site.
    }

    /** Milliseconds remaining before this kit may be claimed, or 0 when it is ready. */
    public long cooldownRemaining(String name, long nowMillis) {
        Kit kit = find(name);
        if (kit == null) return 0;

        Long last = lastClaim.get(kit.key());
        if (last == null) return 0;

        long readyAt = last + kit.cooldownSeconds() * 1000L;
        return Math.max(0, readyAt - nowMillis);
    }

    public boolean ready(String name, long nowMillis) {
        return cooldownRemaining(name, nowMillis) <= 0;
    }

    public int claims(String name) {
        Kit kit = find(name);
        String key = kit == null ? "" : kit.key();
        return claimCount.getOrDefault(key, 0);
    }

    /** The kits that are ready to claim right now, soonest-cooldown first among the rest. */
    public List<Kit> readyKits(long nowMillis) {
        List<Kit> ready = new ArrayList<>();
        for (Kit kit : kits.values()) {
            if (ready(kit.name(), nowMillis)) ready.add(kit);
        }
        return ready;
    }

    /** A one-line status for the HUD, terminal and webhook. */
    public String status(long nowMillis) {
        if (kits.isEmpty()) return "no kits configured";
        int ready = readyKits(nowMillis).size();
        return "%d kit(s), %d ready".formatted(kits.size(), ready);
    }

    /** A detailed per-kit listing for the terminal. */
    public List<String> describe(long nowMillis) {
        List<String> lines = new ArrayList<>();
        for (Kit kit : kits.values()) {
            long remaining = cooldownRemaining(kit.name(), nowMillis);
            lines.add("%s (%s): %s, claimed %d time(s)".formatted(
                kit.name(), kit.claimLine(),
                remaining <= 0 ? "ready" : "ready in %d:%02d".formatted(remaining / 60000, (remaining / 1000) % 60),
                claims(kit.name())));
        }
        return lines;
    }
}
