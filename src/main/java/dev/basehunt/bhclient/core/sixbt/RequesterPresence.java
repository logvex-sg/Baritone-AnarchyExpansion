package dev.basehunt.bhclient.core.sixbt;

/**
 * Tracks whether the player a kit bot is delivering to has actually left, rather than merely not being
 * visible yet.
 *
 * <p>A delivery begins with a {@code /tpa} to someone who is, by definition, out of render range - that is
 * the whole point of the teleport. If "the requester is not in the loaded world right now" were read as
 * "the requester left", every delivery would abort a few seconds after it started, which is precisely the
 * "the bot does not react" symptom. So a missing player only counts as gone once they have actually been
 * seen at least once (which happens when the teleport lands).
 *
 * <p>Pure and tick-driven, so the "not seen yet is not gone", "seen then gone is gone" and "gone then back
 * is not gone" cases are all covered by the headless suite.
 */
public final class RequesterPresence {

    private final int goneTicks;
    private boolean seen;
    private int missingTicks;

    /**
     * @param goneTicks how many consecutive ticks the requester must be missing (after having been seen)
     *                  before they count as gone
     */
    public RequesterPresence(int goneTicks) {
        this.goneTicks = Math.max(1, goneTicks);
    }

    /** Forgets everything, for the start of a new delivery. */
    public void reset() {
        seen = false;
        missingTicks = 0;
    }

    /**
     * Records one observation and reports whether the requester should now be treated as gone.
     *
     * @param visible whether the requester is currently in the loaded world
     * @return true only when they have been seen before and have been missing for longer than {@code goneTicks}
     */
    public boolean observe(boolean visible) {
        if (visible) {
            seen = true;
            missingTicks = 0;
            return false;
        }
        // Never seen yet: they are simply still out of range, which the /tpa is there to fix.
        if (!seen) return false;
        missingTicks++;
        return missingTicks > goneTicks;
    }

    /** Whether the requester has been seen at least once during this delivery. */
    public boolean hasBeenSeen() {
        return seen;
    }
}
