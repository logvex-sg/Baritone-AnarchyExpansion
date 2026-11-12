package dev.basehunt.bhclient.core.logic;

/**
 * Decides whether a whole-database export can be read by this build.
 *
 * <p>Every store already tolerates a single corrupt record, but a whole export carries one {@code
 * formatVersion} for all of them. Two failures are possible and only one of them is safe to ignore:
 *
 * <ul>
 *   <li>an <b>older</b> export ({@code formatVersion} below the current one) is still readable, because
 *       every section decoder ignores the fields it does not know and fills the rest with defaults. It is
 *       accepted and reported as {@link Verdict#LEGACY} so the import can say so rather than silently
 *       pretending the file is current;</li>
 *   <li>a <b>newer</b> export (a tag written by a later build that may hold fields, or section shapes, this
 *       build cannot interpret) is refused. Importing it would half-apply a format we do not understand and
 *       quietly drop data, which is worse than declining.</li>
 * </ul>
 *
 * <p>The version is read with {@link RecordCodec#readVersion}: an absent or nonsensical value is treated as
 * the current version, so a hand-made tag with no version header is still accepted rather than rejected.
 * The class is pure (NBT only) so the policy is covered by the headless suite.
 */
public final class DatabaseVersion {

    /** The {@code formatVersion} this build writes and is willing to read. */
    public static final int CURRENT = 3;

    /** The key the whole-database tag stores its version under (see {@code DatabaseIO.exportAll}). */
    public static final String KEY = "formatVersion";

    /** What an import should do with a tag carrying a given version. */
    public enum Verdict {
        /** Same version as this build: import as-is. */
        CURRENT,
        /** An older format: readable, but the caller should say it was migrated. */
        LEGACY,
        /** A newer format: refuse, or the import would drop fields it cannot parse. */
        TOO_NEW
    }

    private DatabaseVersion() {
    }

    /** Classifies a whole-database tag's version against {@link #CURRENT}. */
    public static Verdict verdict(int version) {
        if (version > CURRENT) return Verdict.TOO_NEW;
        if (version < CURRENT) return Verdict.LEGACY;
        return Verdict.CURRENT;
    }

    /** Convenience overload reading the version straight out of a tag. */
    public static Verdict verdictOf(net.minecraft.nbt.NbtCompound root) {
        return verdict(versionOf(root));
    }

    /**
     * Reads the whole-database format version out of a tag. Unlike the per-store {@code version} key that
     * {@link RecordCodec#readVersion} handles, a whole-database export writes {@code formatVersion}; an absent
     * or nonsensical value is treated as the current version so a hand-made tag is not rejected.
     */
    public static int versionOf(net.minecraft.nbt.NbtCompound root) {
        if (root == null) return CURRENT;
        int version = root.getInt(KEY, CURRENT);
        return version <= 0 ? CURRENT : version;
    }

    /** True when an import of this version must be refused. */
    public static boolean shouldRefuse(int version) {
        return verdict(version) == Verdict.TOO_NEW;
    }

    /** A one-line explanation for chat, chosen to match the verdict. */
    public static String describe(int version) {
        return switch (verdict(version)) {
            case CURRENT -> "database format v%d (current)".formatted(version);
            case LEGACY -> "database format v%d, upgraded to v%d on import".formatted(version, CURRENT);
            case TOO_NEW -> "database format v%d is newer than this build (v%d); import refused"
                .formatted(version, CURRENT);
        };
    }
}
