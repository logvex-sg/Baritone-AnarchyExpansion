package dev.basehunt.bhclient.core.kit;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a server's chat reply to a kit claim.
 *
 * <p>Servers word their cooldown and denial messages differently, so this matches on the phrases they share
 * ("cooldown", "wait", "already", "permission", "unknown kit") rather than on an exact string. Anything that
 * is not recognised as a denial is treated as neutral, and a claim with no denial is considered to have
 * succeeded once enough ticks have passed - the client cannot read the server's real cooldown, so this is the
 * closest honest signal available.
 *
 * <p>The classification is pure string logic, so the pattern table is covered by the headless suite.
 */
public final class KitResponses {

    /** What a reply means for the pending claim. */
    public enum Verdict {
        /** The server refused because the kit is on cooldown. */
        COOLDOWN,
        /** The server refused for another reason (permissions, unknown kit, full inventory). */
        DENIED,
        /** The server gave the kit. */
        GRANTED,
        /** Not a kit reply; leave the claim pending. */
        NEUTRAL
    }

    private static final List<String> COOLDOWN = List.of(
        "cooldown", "cool down", "on cooldown", "wait ", "waiting", "please wait", "too soon", "try again"
    );

    private static final List<String> DENIED = List.of(
        "no permission", "you do not", "you don't", "not allowed", "unknown kit", "no such kit",
        "invalid kit", "cannot", "can't", "denied", "full inventory", "no space"
    );

    private static final List<String> GRANTED = List.of(
        "you have been given", "you received", "you got", "have been granted", "kit claimed", "enjoy"
    );

    private KitResponses() {
    }

    /** Classifies a chat message. */
    public static Verdict classify(String message) {
        if (message == null || message.isBlank()) return Verdict.NEUTRAL;
        String text = message.toLowerCase(Locale.ROOT);

        for (String phrase : COOLDOWN) if (text.contains(phrase)) return Verdict.COOLDOWN;
        for (String phrase : GRANTED) if (text.contains(phrase)) return Verdict.GRANTED;
        for (String phrase : DENIED) if (text.contains(phrase)) return Verdict.DENIED;

        return Verdict.NEUTRAL;
    }

    /** True when the verdict ends the pending claim (success or refusal). */
    public static boolean resolves(Verdict verdict) {
        return verdict == Verdict.COOLDOWN || verdict == Verdict.DENIED || verdict == Verdict.GRANTED;
    }
}
