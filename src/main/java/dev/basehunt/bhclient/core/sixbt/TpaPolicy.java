package dev.basehunt.bhclient.core.sixbt;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether to accept an incoming 6b6t {@code /tpa} request automatically.
 *
 * <p>6b6t lets players request a teleport to you ({@code /tpa}), and you accept with {@code /tpy} or refuse
 * with {@code /tpn}. A delivery bot wants the opposite behaviour: it is <em>sent</em> to people, so it must
 * accept <em>their</em> requests - but only when it makes sense to. Blindly accepting every request lets any
 * stranger pull the bot away mid-delivery, so acceptance is restricted to an allow-list (and an optional
 * "accept anyone" mode for a bot parked in a safe spot).
 *
 * <p>Pure decision logic, so the allow/deny rules are covered by the headless suite.
 */
public final class TpaPolicy {

    /** What to do with a request. */
    public enum Decision {
        /** Accept it with {@code /tpy}. */
        ACCEPT,
        /** Refuse it with {@code /tpn}. */
        DECLINE,
        /** Do nothing and leave the request for the player. */
        IGNORE
    }

    private final Set<String> allowed = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();
    private final boolean acceptAnyone;

    public TpaPolicy(List<String> allowList, List<String> blockList, boolean acceptAnyone) {
        if (allowList != null) {
            for (String name : allowList) if (name != null && !name.isBlank()) {
                allowed.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (blockList != null) {
            for (String name : blockList) if (name != null && !name.isBlank()) {
                blocked.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.acceptAnyone = acceptAnyone;
    }

    /** A policy that accepts nobody. */
    public static TpaPolicy denyAll() {
        return new TpaPolicy(List.of(), List.of(), false);
    }

    /** The decision for a request from {@code player}. */
    public Decision decide(String player) {
        if (player == null || player.isBlank()) return Decision.IGNORE;
        String key = player.trim().toLowerCase(Locale.ROOT);

        if (blocked.contains(key)) return Decision.DECLINE;
        if (allowed.contains(key) || acceptAnyone) return Decision.ACCEPT;
        return Decision.IGNORE;
    }

    /** True when the player is explicitly allowed. */
    public boolean isAllowed(String player) {
        return player != null && allowed.contains(player.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isBlocked(String player) {
        return player != null && blocked.contains(player.trim().toLowerCase(Locale.ROOT));
    }

    public Set<String> allowList() {
        return Set.copyOf(allowed);
    }

    public Set<String> blockList() {
        return Set.copyOf(blocked);
    }

    public boolean acceptsAnyone() {
        return acceptAnyone;
    }
}
