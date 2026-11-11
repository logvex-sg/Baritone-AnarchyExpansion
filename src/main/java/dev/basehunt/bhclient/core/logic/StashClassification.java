package dev.basehunt.bhclient.core.logic;

/**
 * Confidence buckets a discovery can fall into.
 *
 * <p>Distinct from the raw score so the GUI, the webhook and the route planner can all speak the same
 * language ("that is a large stash") without re-deriving thresholds.
 */
public enum StashClassification {
    /** Seen but below any reporting threshold. */
    UNCONFIRMED,
    /** A couple of containers; could easily be a mineshaft or a village. */
    POSSIBLE_STASH,
    SMALL_STASH,
    MEDIUM_STASH,
    LARGE_STASH,
    /** Enough storage that it is almost certainly an organised base. */
    MAJOR_STORAGE,
    /** Not storage, but worth a look: an unusual build or a landmark. */
    INTERESTING_STRUCTURE,
    /** Recorded in a previous session and revisited. */
    PREVIOUSLY_DISCOVERED;

    public String describe() {
        return switch (this) {
            case UNCONFIRMED -> "unconfirmed";
            case POSSIBLE_STASH -> "possible stash";
            case SMALL_STASH -> "small stash";
            case MEDIUM_STASH -> "medium stash";
            case LARGE_STASH -> "large stash";
            case MAJOR_STORAGE -> "major storage location";
            case INTERESTING_STRUCTURE -> "interesting structure";
            case PREVIOUSLY_DISCOVERED -> "previously discovered";
        };
    }

    /** True for the tiers worth sending an alert for. */
    public boolean isNoteworthy() {
        return this == MEDIUM_STASH || this == LARGE_STASH || this == MAJOR_STORAGE
            || this == INTERESTING_STRUCTURE;
    }
}
