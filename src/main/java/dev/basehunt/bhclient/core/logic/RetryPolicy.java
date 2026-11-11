package dev.basehunt.bhclient.core.logic;

/**
 * Retry/backoff state for an action that can fail repeatedly, such as deploying an elytra.
 *
 * <p>The rule the whole feature depends on: an action must never be retried forever at full speed. This
 * tracks attempts, enforces a minimum gap between them, and gives up after a configurable limit so the
 * caller can escalate (swap elytra, cancel the route, log out) instead of hammering a doomed attempt.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    private int attempts;
    private long lastAttemptAt;
    private long nextAllowedAt;

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(0, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
    }

    /** True when another attempt is allowed now: under the limit and past the backoff window. */
    public boolean canAttempt(long nowMs) {
        return attempts < maxAttempts && nowMs >= nextAllowedAt;
    }

    public boolean exhausted() {
        return attempts >= maxAttempts;
    }

    public int attempts() {
        return attempts;
    }

    /** Records an attempt and schedules the next allowed time using exponential backoff. */
    public void recordAttempt(long nowMs) {
        attempts++;
        lastAttemptAt = nowMs;

        long delay = baseDelayMs << Math.min(attempts - 1, 6);
        nextAllowedAt = nowMs + Math.min(delay, maxDelayMs);
    }

    /** Clears the policy after a success so it is ready for the next independent failure. */
    public void reset() {
        attempts = 0;
        nextAllowedAt = 0;
    }

    public long lastAttemptAt() {
        return lastAttemptAt;
    }

    public long nextAllowedAt() {
        return nextAllowedAt;
    }

    /** Milliseconds until another attempt is allowed, or 0 when one is allowed now. */
    public long millisUntilNextAttempt(long nowMs) {
        return Math.max(0, nextAllowedAt - nowMs);
    }
}
