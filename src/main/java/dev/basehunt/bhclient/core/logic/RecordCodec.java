package dev.basehunt.bhclient.core.logic;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Tolerant reader for the record lists the persisted stores keep in NBT.
 *
 * <p>All four databases ({@code StashManager}, {@code PlayerTracker}, {@code EnderChestStore},
 * {@code ExplorationStore}) serialise as a versioned tag holding a list of compounds. The rules that make
 * that safe are the same for each, so they live here once:
 *
 * <ul>
 *   <li>a record that fails to decode is skipped, never fatal - one corrupt entry must not cost the user
 *       the rest of their database;</li>
 *   <li>a decoder that throws is treated exactly like one that returns null;</li>
 *   <li>a missing or malformed list decodes to an empty list rather than a crash;</li>
 *   <li>the file's format version is read defensively so a future version can be detected instead of
 *       silently misread.</li>
 * </ul>
 *
 * <p>This class touches only NBT, which loads without a Fabric runtime, so the recovery behaviour is
 * covered by the headless logic suite rather than only by a live client.
 */
public final class RecordCodec {

    /** Decodes one record, returning null when the tag is not usable. */
    public interface Decoder<T> {
        T decode(NbtCompound tag);
    }

    /**
     * Outcome of decoding a list.
     *
     * @param records the records that decoded successfully, in file order
     * @param skipped how many entries were rejected
     */
    public record Result<T>(List<T> records, int skipped) {
        public int size() {
            return records.size();
        }

        public boolean anySkipped() {
            return skipped > 0;
        }
    }

    private RecordCodec() {
    }

    /**
     * Decodes a named compound list out of a store's root tag.
     *
     * @param root    the store tag, or null
     * @param key     the list key inside the root tag
     * @param decoder per-record decoder
     */
    public static <T> Result<T> decodeList(NbtCompound root, String key, Decoder<T> decoder) {
        if (root == null) return new Result<>(List.of(), 0);
        return decode(root.getListOrEmpty(key), decoder);
    }

    /** Decodes a list directly, skipping entries that are not compounds or fail to decode. */
    public static <T> Result<T> decode(NbtList list, Decoder<T> decoder) {
        List<T> records = new ArrayList<>();

        if (list == null) return new Result<>(records, 0);

        int skipped = 0;

        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound compound)) {
                skipped++;
                continue;
            }

            T record;
            try {
                record = decoder.decode(compound);
            } catch (RuntimeException e) {
                record = null;
            }

            if (record == null) skipped++;
            else records.add(record);
        }

        return new Result<>(records, skipped);
    }

    /** The file's format version, or {@code fallback} when it is absent or nonsense. */
    public static int readVersion(NbtCompound root, int fallback) {
        if (root == null) return fallback;

        int version = root.getInt("version", fallback);
        return version <= 0 ? fallback : version;
    }

    /**
     * True when the file was written by a newer build than this one understands. The caller decides what to
     * do about it; the stores keep reading the records they recognise rather than refusing to load, because
     * a partially readable database is more useful than none.
     */
    public static boolean isFromNewerVersion(NbtCompound root, int supported) {
        return readVersion(root, supported) > supported;
    }

    /** True when a file predates the current format and should be migrated. */
    public static boolean needsMigration(NbtCompound root, int current) {
        return readVersion(root, current) < current;
    }
}
