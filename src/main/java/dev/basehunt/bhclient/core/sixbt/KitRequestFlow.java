package dev.basehunt.bhclient.core.sixbt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides which whisper a kit bot sends back for a request, before anything is queued.
 *
 * <p>A request can fail in four different ways - the player is not allowed, the kit does not exist, they asked
 * again too soon, or the queue is full - and each one deserves a different reply. Working that out inline in
 * the module makes the replies untestable and easy to get out of order; keeping it here means the exact
 * "refuse a stranger / report a bad kit / start the cooldown message" order is pinned by the headless suite.
 *
 * <p>The order is deliberate: access is checked before anything else, so a stranger learns nothing about which
 * kits exist. A bad kit name is reported before queue state, because "that kit does not exist" is more useful
 * than "you are in place 1".
 */
public final class KitRequestFlow {

    /** The reply to send, and the values its template needs. */
    public record Reply(KitMessages.Kind kind, Map<String, String> vars) {
        public Reply {
            vars = vars == null ? Map.of() : Map.copyOf(vars);
        }
    }

    private KitRequestFlow() {
    }

    /**
     * Works out the reply for a request.
     *
     * @param decision        the access decision
     * @param kitExists       whether a loadout is registered under the requested name
     * @param queueRefusal    the queue's refusal reason ({@code "full"}, {@code "cooldown"},
     *                        {@code "already queued"}) or null when the request would be accepted
     * @param position        the requester's 1-based queue position (0 when not queued)
     * @param cooldownSeconds seconds left on the requester's cooldown
     * @param capacity        the queue capacity
     * @return the reply, or null when the bot should say nothing
     */
    public static Reply evaluate(KitRequestPolicy.Decision decision, boolean kitExists, String queueRefusal,
                                 int position, long cooldownSeconds, int capacity) {
        if (decision != KitRequestPolicy.Decision.ALLOW) {
            return new Reply(KitMessages.Kind.NOT_WHITELISTED, Map.of());
        }
        if (!kitExists) {
            return new Reply(KitMessages.Kind.UNKNOWN_KIT, Map.of());
        }
        if ("full".equals(queueRefusal)) {
            return new Reply(KitMessages.Kind.QUEUE_FULL, Map.of("max", String.valueOf(capacity)));
        }
        if ("cooldown".equals(queueRefusal)) {
            long minutes = (Math.max(1, cooldownSeconds) + 59) / 60;
            return new Reply(KitMessages.Kind.COOLDOWN, Map.of("minutes", String.valueOf(minutes)));
        }

        // Accepted, or already queued. Next in line gets "grabbing the kit"; anyone behind them needs to
        // know where they stand.
        int place = position > 0 ? position : 1;
        if (place <= 1) return new Reply(KitMessages.Kind.GRABBING, Map.of());
        return new Reply(KitMessages.Kind.QUEUED, Map.of("place", String.valueOf(place)));
    }

    /** The reply for a request that was accepted into the queue. */
    public static Reply queued(int position) {
        return new Reply(KitMessages.Kind.QUEUED, Map.of("place", String.valueOf(Math.max(1, position))));
    }

    /** The reply for a delivery that could not be completed. */
    public static Reply failed() {
        return new Reply(KitMessages.Kind.FAILED, Map.of());
    }

    /** A mutable map builder for callers that want to add their own placeholders. */
    public static Map<String, String> vars(String... keyValues) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) vars.put(keyValues[i], keyValues[i + 1]);
        }
        return vars;
    }
}
