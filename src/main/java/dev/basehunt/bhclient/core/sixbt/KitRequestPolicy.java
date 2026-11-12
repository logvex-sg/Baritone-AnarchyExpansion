package dev.basehunt.bhclient.core.sixbt;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a player may use the kit bot.
 *
 * <p>A kit maker runs unattended on a busy anarchy server, so its public chat is not a private channel: anyone
 * can type the request prefix. Left open, that lets a stranger pull the bot across the map, drain the kit
 * supply and, because the delivery ends in {@code /kill}, get it killed on demand. The bot is therefore
 * <strong>closed by default</strong>: only names on the allow-list are served, and the allow-list starts
 * empty. {@code allow-anyone} is the deliberate escape hatch for a bot parked in a safe spot.
 *
 * <p>A name on the block-list is always refused, even when {@code allow-anyone} is on, so a known griefer can
 * be shut out without turning the bot off.
 *
 * <p>Pure decision logic, so the rules are covered by the headless suite rather than only being felt live.
 */
public final class KitRequestPolicy {

    /** What to do with a request. */
    public enum Decision {
        /** The requester is whitelisted (or anyone is allowed): serve them. */
        ALLOW,
        /** The requester is explicitly blocked: refuse, and do not answer. */
        BLOCKED,
        /** The requester is not on the allow-list: refuse, and optionally tell them. */
        NOT_WHITELISTED
    }

    private final Set<String> allowed = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();
    private final boolean allowAnyone;

    public KitRequestPolicy(List<String> allowList, List<String> blockList, boolean allowAnyone) {
        if (allowList != null) {
            for (String name : allowList) {
                if (name != null && !name.isBlank()) allowed.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (blockList != null) {
            for (String name : blockList) {
                if (name != null && !name.isBlank()) blocked.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowAnyone = allowAnyone;
    }

    /** A policy that serves nobody - the default. */
    public static KitRequestPolicy denyAll() {
        return new KitRequestPolicy(List.of(), List.of(), false);
    }

    /** The decision for a request from {@code requester}. */
    public Decision decide(String requester) {
        if (requester == null || requester.isBlank()) return Decision.NOT_WHITELISTED;
        String key = requester.trim().toLowerCase(Locale.ROOT);

        if (blocked.contains(key)) return Decision.BLOCKED;
        if (allowed.contains(key) || allowAnyone) return Decision.ALLOW;
        return Decision.NOT_WHITELISTED;
    }

    /** True when the requester may be served. */
    public boolean isAllowed(String requester) {
        return decide(requester) == Decision.ALLOW;
    }

    public boolean isBlocked(String requester) {
        return requester != null && blocked.contains(requester.trim().toLowerCase(Locale.ROOT));
    }

    public Set<String> allowList() {
        return Set.copyOf(allowed);
    }

    public Set<String> blockList() {
        return Set.copyOf(blocked);
    }

    public boolean allowsAnyone() {
        return allowAnyone;
    }

    /** True when the bot would serve nobody (no allow-list and {@code allow-anyone} off). */
    public boolean servesNobody() {
        return allowed.isEmpty() && !allowAnyone;
    }

    /** A one-line status for the HUD and terminal. */
    public String statusLine() {
        if (allowAnyone) {
            return "open to anyone" + (blocked.isEmpty() ? "" : ", %d blocked".formatted(blocked.size()));
        }
        if (allowed.isEmpty()) return "closed (no one whitelisted)";
        return "%d whitelisted".formatted(allowed.size());
    }
}
