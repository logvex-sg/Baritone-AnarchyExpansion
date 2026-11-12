package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.RecordCodec;
import dev.basehunt.bhclient.core.sixbt.HomeBook;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent 6b6t home book, per server.
 *
 * <p>The server owns the real homes; this is the client's memory of what it created, so the addon can warn
 * before {@code /sethome} fails on a full list and can pick a sensible name. Keyed per server like every other
 * store, decoded defensively.
 */
public class SixbtServerStore extends System<SixbtServerStore> {
    private static final int DB_VERSION = 1;

    private final List<Record> records = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public SixbtServerStore() {
        super("base-hunting-6b6t");
    }

    public static SixbtServerStore get() {
        return Systems.get(SixbtServerStore.class);
    }

    /** Builds a home book for a server. */
    public HomeBook homesFor(String server) {
        HomeBook book = new HomeBook();
        for (Record record : records) {
            if (!record.server.equals(server)) continue;
            book.put(new HomeBook.Home(record.name, record.dimension, record.x, record.y, record.z, record.createdAt));
        }
        return book;
    }

    /** Stores a home for a server, replacing any earlier one with the same name. */
    public void put(String server, HomeBook.Home home) {
        if (home == null) return;
        for (Record record : records) {
            if (record.server.equals(server) && record.name.equalsIgnoreCase(home.name())) {
                record.name = home.name();
                record.dimension = home.dimension();
                record.x = home.x();
                record.y = home.y();
                record.z = home.z();
                record.createdAt = home.createdAt();
                save();
                return;
            }
        }
        records.add(new Record(server, home));
        save();
    }

    public boolean remove(String server, String name) {
        if (name == null) return false;
        boolean removed = records.removeIf(record -> record.server.equals(server)
            && record.name.equalsIgnoreCase(name));
        if (removed) save();
        return removed;
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
        tag.put("homes", list);
        return tag;
    }

    @Override
    public SixbtServerStore fromTag(NbtCompound tag) {
        records.clear();

        var result = RecordCodec.decodeList(tag, "homes", Record::fromTag);
        records.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);
        return this;
    }

    /** One stored home. */
    public static class Record {
        public String server;
        public String name;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public long createdAt;

        public Record(String server, HomeBook.Home home) {
            this.server = server;
            this.name = home.name();
            this.dimension = home.dimension();
            this.x = home.x();
            this.y = home.y();
            this.z = home.z();
            this.createdAt = home.createdAt();
        }

        public HomeBook.Home home() {
            return new HomeBook.Home(name, dimension, x, y, z, createdAt);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("server", server);
            tag.putString("name", name);
            tag.putString("dimension", dimension == null ? "overworld" : dimension);
            tag.putInt("x", x);
            tag.putInt("y", y);
            tag.putInt("z", z);
            tag.putLong("createdAt", createdAt);
            return tag;
        }

        public static Record fromTag(NbtCompound tag) {
            String name = tag.getString("name", "");
            if (name.isBlank()) return null;
            Record record = new Record(tag.getString("server", "unknown"),
                new HomeBook.Home(name, "overworld", 0, 0, 0, 0));
            record.dimension = tag.getString("dimension", "overworld");
            record.x = tag.getInt("x", 0);
            record.y = tag.getInt("y", 0);
            record.z = tag.getInt("z", 0);
            record.createdAt = tag.getLong("createdAt", 0);
            return record;
        }
    }
}
