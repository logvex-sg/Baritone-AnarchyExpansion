package dev.basehunt.bhclient.core.logic;

/**
 * Tracks a single container operation as it moves through open, click, verify and settle.
 *
 * <p>Minecraft only tells the client that a click was <em>sent</em>; whether the server accepted it shows up
 * later, as the inventory changing. This class encodes the wait-for-the-server discipline that makes the
 * runtime safe: an operation is not done because its clicks went out, but because the observed inventory
 * stopped changing and, when a target fingerprint was known, matches it.
 *
 * <p>It is deliberately pure - it takes ticks and fingerprints, never a client - so the state transitions
 * (including the "server never answered" timeout and the "content settled but did not match" case) are
 * covered by the headless suite.
 */
public final class OperationState {

    public enum Phase {
        /** Waiting for the container screen to open. */
        OPENING,
        /** Sending the planned clicks. */
        CLICKING,
        /** Clicks are out; waiting for the container contents to settle and match. */
        VERIFYING,
        /** Finished successfully. */
        DONE,
        /** Gave up. See {@link #failure()}. */
        FAILED,
        /** Abandoned by the caller before completion. */
        CANCELLED
    }

    private final String label;
    private final long startTick;
    private final long timeoutTicks;
    private final String expectedFingerprint;

    private Phase phase = Phase.OPENING;
    private long lastChangeTick;
    private int stableTicks;
    private String observedFingerprint = "";
    private int clicksSent;
    private int clicksRejected;
    private String failure;

    private OperationState(String label, long startTick, long timeoutTicks, String expectedFingerprint) {
        this.label = label;
        this.startTick = startTick;
        this.lastChangeTick = startTick;
        this.timeoutTicks = Math.max(1, timeoutTicks);
        this.expectedFingerprint = expectedFingerprint == null ? "" : expectedFingerprint;
    }

    public static OperationState begin(String label, long startTick, long timeoutTicks, String expectedFingerprint) {
        return new OperationState(label, startTick, timeoutTicks, expectedFingerprint);
    }

    public String label() {
        return label;
    }

    public Phase phase() {
        return phase;
    }

    public boolean finished() {
        return phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELLED;
    }

    public boolean failed() {
        return phase == Phase.FAILED;
    }

    public String failure() {
        return failure;
    }

    public int clicksSent() {
        return clicksSent;
    }

    public int clicksRejected() {
        return clicksRejected;
    }

    public String expectedFingerprint() {
        return expectedFingerprint;
    }

    public String observedFingerprint() {
        return observedFingerprint;
    }

    /** The container opened; begin clicking. */
    public void opened() {
        if (phase == Phase.OPENING) phase = Phase.CLICKING;
    }

    /** The container failed to open within the timeout. */
    public void openTimedOut() {
        fail("the container did not open");
    }

    /** Records that a click was sent. */
    public void clickSent() {
        clicksSent++;
    }

    /**
     * Records that a click was refused locally (the slot was gone, the cursor was occupied, the screen
     * changed). A refused click is not retried blindly; too many in a row fails the operation.
     */
    public void clickRejected(String reason) {
        clicksRejected++;
        if (clicksRejected >= 3) fail("the server rejected " + clicksRejected + " clicks (" + reason + ")");
    }

    /** All planned clicks have been sent; now wait for the result to settle. */
    public void clicksComplete() {
        if (phase == Phase.CLICKING) phase = Phase.VERIFYING;
    }

    /**
     * Feeds the current container fingerprint for this tick.
     *
     * @return true when the operation just finished
     */
    public boolean observe(String fingerprint, long tick, int settleTicks, int minStableTicks) {
        if (finished()) return true;

        String next = fingerprint == null ? "" : fingerprint;

        if (!next.equals(observedFingerprint)) {
            observedFingerprint = next;
            lastChangeTick = tick;
            stableTicks = 0;
        } else {
            stableTicks++;
        }

        if (tick - startTick > timeoutTicks) {
            fail("timed out after " + timeoutTicks + " ticks");
            return true;
        }

        if (phase == Phase.OPENING) {
            // Nothing to verify yet; the caller drives OPENING -> CLICKING.
            return false;
        }

        if (stableTicks < Math.max(1, minStableTicks)) return false;

        if (expectedFingerprint.isEmpty() || observedFingerprint.equals(expectedFingerprint)) {
            phase = Phase.DONE;
            return true;
        }

        // The contents settled but do not match what the plan promised. Give the server a little longer, then
        // fail rather than reporting a success that did not happen.
        if (tick - lastChangeTick > settleTicks) {
            fail("the container settled in an unexpected state");
            return true;
        }

        return false;
    }

    /** Abandons the operation. Safe to call at any point. */
    public void cancel() {
        if (!finished()) phase = Phase.CANCELLED;
    }

    private void fail(String reason) {
        if (finished()) return;
        phase = Phase.FAILED;
        failure = reason;
    }

    /** A short human-readable state, for the HUD and the monitor. */
    public String describe() {
        return switch (phase) {
            case OPENING -> label + ": opening";
            case CLICKING -> label + ": %d/%d clicks".formatted(clicksSent, clicksSent + clicksRejected);
            case VERIFYING -> label + ": verifying";
            case DONE -> label + ": done (%d clicks)".formatted(clicksSent);
            case FAILED -> label + ": failed - " + failure;
            case CANCELLED -> label + ": cancelled";
        };
    }
}
