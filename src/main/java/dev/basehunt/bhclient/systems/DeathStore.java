package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.DeathMemory;
import dev.basehunt.bhclient.core.logic.RecordCodec;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent death memory, keyed per server.
 *
 * <p>A death is only recoverable if you remember where it was, and a client restart between the death and
 * the recovery run must not lose that. Records are stored per server through {@link RecordCodec}, so a single
 * corrupt record is skipped rather than costing you the rest of your death list, and the in-memory list is
 * bounded by {@link DeathMemory} so an unlucky session cannot grow the file without limit.
 */
public class DeathStore extends System<DeathStore> {
    private static final int DB_VERSION = 1;
    private static final int CAP = 64;

    private final DeathMemory memory = new DeathMemory(CAP);
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public DeathStore() {
        super("base-hunting-deaths");
    }

    public static DeathStore get() {
        return Systems.get(DeathStore.class);
    }

    public DeathMemory memory() {
        return memory;
    }

    /** The deaths recorded for a server, newest first. */
    public List<DeathMemory.DeathRecord> forServer(String server) {
        return memory.forServer(server);
    }

    /** Records a death and persists it. */
    public DeathMemory.DeathRecord record(String server, String dimension, int x, int y, int z,
                                          String cause, int carriedItems, long timestamp) {
        DeathMemory.DeathRecord record = memory.add(server, dimension, x, y, z, cause, carriedItems, timestamp);
        save();
        return record;
    }

    /** Marks a death recovered and persists the change. */
    public boolean markRecovered(long id) {
        boolean changed = memory.markRecovered(id);
        if (changed) save();
        return changed;
    }

    public int removeWhere(java.util.function.Predicate<DeathMemory.DeathRecord> predicate) {
        int removed = memory.removeWhere(predicate);
        if (removed > 0) save();
        return removed;
    }

    public int lastSkippedRecords() {
        return lastSkippedRecords;
    }

    public int lastLoadedVersion() {
        return lastLoadedVersion;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        NbtList list = new NbtList();
        for (DeathMemory.DeathRecord record : memory.all()) list.add(toTag(record));
        tag.put("deaths", list);
        return tag;
    }

    @Override
    public DeathStore fromTag(NbtCompound tag) {
        var decoded = RecordCodec.decodeList(tag, "deaths", this::decodeRecord);
        memory.replaceAll(decoded.records());

        lastSkippedRecords = decoded.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);

        if (decoded.anySkipped()) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable death record(s) while loading.", decoded.skipped());
        }

        return this;
    }

    private NbtCompound toTag(DeathMemory.DeathRecord record) {
        NbtCompound tag = new NbtCompound();
        tag.putLong("id", record.id());
        tag.putString("server", record.server());
        tag.putString("dimension", record.dimension());
        tag.putIntArray("pos", new int[] {record.x(), record.y(), record.z()});
        tag.putLong("time", record.timestamp());
        tag.putString("cause", record.cause());
        tag.putInt("carried", record.carriedItems());
        tag.putBoolean("recovered", record.recovered());
        return tag;
    }

    private DeathMemory.DeathRecord decodeRecord(NbtCompound tag) {
        int[] pos = tag.getIntArray("pos").orElse(null);
        if (pos == null || pos.length != 3) return null;

        return new DeathMemory.DeathRecord(
            tag.getLong("id", 0),
            tag.getString("server", "unknown"),
            DeathMemory.normalizeDimension(tag.getString("dimension", "unknown")),
            pos[0], pos[1], pos[2],
            tag.getLong("time", 0),
            tag.getString("cause", ""),
            tag.getInt("carried", 0),
            tag.getBoolean("recovered", false)
        );
    }
}
