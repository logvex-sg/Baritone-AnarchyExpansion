package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.RecordCodec;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent 6b6t player sightings, per server, with a hard cap so the file cannot grow without limit.
 *
 * <p>Anarchy servers are full of players who appear once and never again. Keeping all of them forever would
 * grow the file for as long as the client runs, so the store keeps the most recent {@link #CAP} records and
 * evicts the oldest. Each record is a name, where and when they were last seen, and how many times they have
 * been seen - enough to answer "have I met this player before?" without pretending to be a full player log.
 */
public class SixbtPlayerStore extends System<SixbtPlayerStore> {
    private static final int DB_VERSION = 1;
    private static final int CAP = 5000;

    private final List<Record> records = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public SixbtPlayerStore() {
        super("base-hunting-6b6t-players");
    }

    public static SixbtPlayerStore get() {
        return Systems.get(SixbtPlayerStore.class);
    }

    /** Records a sighting of a player, updating an existing record or adding a new one. */
    public void record(String server, String name, String dimension, int x, int y, int z, long when) {
        if (name == null || name.isBlank()) return;
        for (Record record : records) {
            if (record.server.equals(server) && record.name.equalsIgnoreCase(name)) {
                record.dimension = dimension;
                record.x = x;
                record.y = y;
                record.z = z;
                record.lastSeen = when;
                record.sightings++;
                save();
                return;
            }
        }
        records.add(new Record(server, name, dimension, x, y, z, when, when, 1));
        while (records.size() > CAP) records.remove(0);
        save();
    }

    /** The records for a server, newest first. */
    public List<Record> forServer(String server) {
        List<Record> out = new ArrayList<>();
        for (Record record : records) if (record.server.equals(server)) out.add(record);
        out.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return out;
    }

    /** Records for a server whose name contains the query, case-insensitively. */
    public List<Record> search(String server, String query) {
        List<Record> out = new ArrayList<>();
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        for (Record record : forServer(server)) {
            if (needle.isEmpty() || record.name.toLowerCase(java.util.Locale.ROOT).contains(needle)) out.add(record);
        }
        return out;
    }

    public int removeWhere(java.util.function.Predicate<Record> predicate) {
        int before = records.size();
        records.removeIf(predicate);
        int removed = before - records.size();
        if (removed > 0) save();
        return removed;
    }

    public int size() {
        return records.size();
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
        for (Record record : records) list.add(record.toTag());
        tag.put("players", list);
        return tag;
    }

    @Override
    public SixbtPlayerStore fromTag(NbtCompound tag) {
        records.clear();

        var result = RecordCodec.decodeList(tag, "players", Record::fromTag);
        records.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);
        return this;
    }

    /** One player sighting record. */
    public static class Record {
        public String server;
        public String name;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public long firstSeen;
        public long lastSeen;
        public int sightings;

        public Record(String server, String name, String dimension, int x, int y, int z,
                      long firstSeen, long lastSeen, int sightings) {
            this.server = server;
            this.name = name;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.sightings = sightings;
        }

        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }

        public String describe() {
            return "%s [%s] %s, seen %dx".formatted(name, dimension, coords(), sightings);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("server", server);
            tag.putString("name", name);
            tag.putString("dimension", dimension == null ? "overworld" : dimension);
            tag.putInt("x", x);
            tag.putInt("y", y);
            tag.putInt("z", z);
            tag.putLong("firstSeen", firstSeen);
            tag.putLong("lastSeen", lastSeen);
            tag.putInt("sightings", sightings);
            return tag;
        }

        public static Record fromTag(NbtCompound tag) {
            String name = tag.getString("name", "");
            if (name.isBlank()) return null;
            return new Record(tag.getString("server", "unknown"), name,
                tag.getString("dimension", "overworld"),
                tag.getInt("x", 0), tag.getInt("y", 0), tag.getInt("z", 0),
                tag.getLong("firstSeen", 0), tag.getLong("lastSeen", 0), tag.getInt("sightings", 1));
        }
    }
}
