package dev.basehunt.bhclient.core.logic;

/**
 * Decides when a failed capability probe should be retried.
 *
 * <p>This exists because of a real bug: {@code BaritoneHelper} latched the first probe failure for the
 * client's lifetime. The HUD read Baritone's health on the very first rendered frame, before Baritone had
 * finished initialising, so the probe recorded "unusable" and every module that gates on
 * {@code BaritoneHelper.usable()} disabled itself the moment it was enabled. Retrying a failure after a
 * short delay turns that permanent false negative into a transient one.
 *
 * <p>Kept free of Minecraft and Baritone types so the timing rule is verifiable headlessly.
 */
public final class ProbeRetry {
    /** How long a failed probe is trusted before it is retried, in milliseconds. */
    public static final long DEFAULT_RETRY_MS = 5_000L;

    private ProbeRetry() {
    }

    /**
     * True when a probe should run now.
     *
     * @param everProbed      whether a probe has run at least once
     * @param succeeded       whether the last probe succeeded
     * @param lastFailureAt   wall-clock time of the last failure, in milliseconds
     * @param now             current wall-clock time, in milliseconds
     * @param retryMs         how long a failure stays trusted; values below 1 fall back to the default
     */
    public static boolean shouldProbe(boolean everProbed, boolean succeeded, long lastFailureAt,
                                      long now, long retryMs) {
        if (!everProbed) return true;
        if (succeeded) return false;
        long delay = retryMs > 0 ? retryMs : DEFAULT_RETRY_MS;
        return now - lastFailureAt >= delay;
    }
}
