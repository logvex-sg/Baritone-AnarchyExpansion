package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Keeps a per-server log of players the client has seen, where they were first and last spotted and when.
 *
 * <p>Anarchy servers rotate alt accounts constantly, so entries are matched on UUID and the username is
 * only ever used as a display label.
 */
public class PlayerTracker extends System<PlayerTracker> {
    private static final int DB_VERSION = 2;
    private static final int MAX_ENTRIES_PER_SERVER = 5000;
    private static final int MAX_SIGHTINGS = 4000;

    private final List<Entry> entries = new ArrayList<>();
    /** Bounded log of individual sightings, used for the player-history view and location visualisation. */
    private final List<Sighting> sightings = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public PlayerTracker() {
        super("base-hunting-players");
    }

    public static PlayerTracker get() {
        return Systems.get(PlayerTracker.class);
    }

    public List<Entry> getAll() {
        return entries;
    }

    public List<Sighting> allSightings() {
        return sightings;
    }

    /** Sightings for a single player on the current server, newest first. */
    public List<Sighting> sightingsFor(UUID uuid) {
        String server = ServerUtils.serverAddress();
        List<Sighting> result = new ArrayList<>();

        for (Sighting sighting : sightings) {
            if (sighting.server.equals(server) && sighting.uuid.equals(uuid)) result.add(sighting);
        }

        result.sort(Comparator.comparingLong((Sighting s) -> -s.time));
        return result;
    }

    /** Case-insensitive substring search over the current server's known names. */
    public List<Entry> search(String query) {
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT).trim();
        List<Entry> result = new ArrayList<>();

        for (Entry entry : forCurrentServer()) {
            if (needle.isEmpty() || entry.name.toLowerCase(java.util.Locale.ROOT).contains(needle)) result.add(entry);
        }

        return result;
    }

    /** Drops entries and sightings older than the given age. Returns how many entries were removed. */
    public int expireOlderThan(long hours) {
        if (hours <= 0) return 0;

        long cutoff = java.lang.System.currentTimeMillis() - hours * 3_600_000L;
        int before = entries.size();
        entries.removeIf(entry -> entry.lastSeen < cutoff);
        sightings.removeIf(sighting -> sighting.time < cutoff);

        int removed = before - entries.size();
        if (removed > 0) save();
        return removed;
    }

    public List<Entry> forCurrentServer() {
        String server = ServerUtils.serverAddress();
        List<Entry> result = new ArrayList<>();

        for (Entry entry : entries) {
            if (entry.server.equals(server)) result.add(entry);
        }

        result.sort(Comparator.comparingLong((Entry e) -> -e.lastSeen));
        return result;
    }

    public Entry get(UUID uuid) {
        String server = ServerUtils.serverAddress();

        for (Entry entry : entries) {
            if (entry.uuid.equals(uuid) && entry.server.equals(server)) return entry;
        }

        return null;
    }

    public Entry track(UUID uuid, String name, int x, int y, int z) {
        Entry entry = get(uuid);
        if (entry == null) {
            entry = new Entry(uuid, name, ServerUtils.serverAddress());
            entry.dimension = ServerUtils.dimension();
            entry.firstX = x;
            entry.firstY = y;
            entry.firstZ = z;
            entries.add(entry);
            trim(entry.server);
        }

        entry.name = name;
        entry.lastX = x;
        entry.lastY = y;
        entry.lastZ = z;
        entry.lastSeen = java.lang.System.currentTimeMillis();
        entry.dimension = ServerUtils.dimension();
        entry.sightings++;

        recordSighting(uuid, name, x, y, z);
        return entry;
    }

    /**
     * Appends a sighting to the bounded history, unless the same player was already recorded at the same
     * place within the last minute. Sightings are only useful as a location trail, so a stationary player
     * must not fill the whole history.
     */
    public void recordSighting(UUID uuid, String name, int x, int y, int z) {
        String server = ServerUtils.serverAddress();
        long now = java.lang.System.currentTimeMillis();

        for (int i = sightings.size() - 1; i >= 0; i--) {
            Sighting last = sightings.get(i);
            if (!last.uuid.equals(uuid) || !last.server.equals(server)) continue;

            if (now - last.time < 60_000L && last.x == x && last.y == y && last.z == z) return;
            break;
        }

        sightings.add(new Sighting(uuid, name, server, ServerUtils.dimension(), x, y, z));
        while (sightings.size() > MAX_SIGHTINGS) sightings.remove(0);
    }

    /** Marks the player as having logged out; the next sighting starts a new session. */
    public void markLoggedOut(UUID uuid, int x, int y, int z) {
        Entry entry = get(uuid);
        if (entry == null) return;

        entry.logoutX = x;
        entry.logoutY = y;
        entry.logoutZ = z;
        entry.logoutTime = java.lang.System.currentTimeMillis();
        entry.logoutDimension = ServerUtils.dimension();
        entry.sessionsSeen++;

        save();
    }

    public void markLoggedIn(UUID uuid) {
        Entry entry = get(uuid);
        if (entry == null) return;

        entry.lastLogin = java.lang.System.currentTimeMillis();
        save();
    }

    public Entry findByLogoutLocation(int x, int y, int z, int radius) {
        String server = ServerUtils.serverAddress();
        long now = java.lang.System.currentTimeMillis();

        Entry best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entry entry : entries) {
            if (!entry.server.equals(server)) continue;
            if (entry.logoutTime <= 0) continue;
            if (now - entry.logoutTime > 1000L * 60 * 60 * 12) continue;

            double dx = entry.logoutX - x;
            double dy = entry.logoutY - y;
            double dz = entry.logoutZ - z;
            double distance = dx * dx + dy * dy + dz * dz;

            if (distance <= (double) radius * radius && distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }

        return best;
    }

    private void trim(String server) {
        List<Entry> serverEntries = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.server.equals(server)) serverEntries.add(entry);
        }

        if (serverEntries.size() <= MAX_ENTRIES_PER_SERVER) return;

        serverEntries.sort(Comparator.comparingLong(e -> e.lastSeen));
        int toRemove = serverEntries.size() - MAX_ENTRIES_PER_SERVER;
        entries.removeAll(serverEntries.subList(0, toRemove));
    }

    public int clearCurrentServer() {
        String server = ServerUtils.serverAddress();
        int before = entries.size();
        entries.removeIf(entry -> entry.server.equals(server));

        int removed = before - entries.size();
        if (removed > 0) save();
        return removed;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        NbtList list = new NbtList();
        for (Entry entry : entries) list.add(entry.toTag());
        tag.put("entries", list);

        NbtList sightingList = new NbtList();
        for (Sighting sighting : sightings) sightingList.add(sighting.toTag());
        tag.put("sightings", sightingList);

        return tag;
    }

    @Override
    public PlayerTracker fromTag(NbtCompound tag) {
        entries.clear();
        sightings.clear();

        // Every record is decoded defensively: a single unreadable entry must not lose the rest of the
        // database, and a decoder that throws is treated exactly like one that returns null.
        var decodedEntries = dev.basehunt.bhclient.core.logic.RecordCodec.decodeList(tag, "entries", Entry::fromTag);
        entries.addAll(decodedEntries.records());

        var decodedSightings = dev.basehunt.bhclient.core.logic.RecordCodec.decodeList(tag, "sightings", Sighting::fromTag);
        sightings.addAll(decodedSightings.records());

        lastSkippedRecords = decodedEntries.skipped() + decodedSightings.skipped();
        lastLoadedVersion = dev.basehunt.bhclient.core.logic.RecordCodec.readVersion(tag, DB_VERSION);

        if (lastSkippedRecords > 0) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable player record(s) while loading.", lastSkippedRecords);
        }

        return this;
    }

    /** Records rejected by the last load. Zero unless the file was partly corrupt. */
    public int lastSkippedRecords() {
        return lastSkippedRecords;
    }

    /** Format version of the file that was last loaded. */
    public int lastLoadedVersion() {
        return lastLoadedVersion;
    }

    /** One observed position of one player at one time. */
    public static class Sighting {
        public UUID uuid;
        public String name;
        public String server;
        public String dimension;
        public int x, y, z;
        public long time;

        public Sighting(UUID uuid, String name, String server, String dimension, int x, int y, int z) {
            this.uuid = uuid;
            this.name = name;
            this.server = server;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = java.lang.System.currentTimeMillis();
        }

        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("uuid", uuid.toString());
            tag.putString("name", name);
            tag.putString("server", server);
            tag.putString("dimension", dimension);
            tag.putIntArray("pos", new int[] {x, y, z});
            tag.putLong("time", time);
            return tag;
        }

        public static Sighting fromTag(NbtCompound tag) {
            String rawUuid = tag.getString("uuid", "");
            if (rawUuid.isBlank()) return null;

            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException e) {
                return null;
            }

            int[] pos = tag.getIntArray("pos").orElse(new int[0]);
            if (pos.length != 3) return null;

            Sighting sighting = new Sighting(uuid, tag.getString("name", "unknown"),
                tag.getString("server", "unknown"), tag.getString("dimension", "unknown"),
                pos[0], pos[1], pos[2]);
            sighting.time = tag.getLong("time", sighting.time);
            return sighting;
        }
    }

    public static class Entry {
        public UUID uuid;
        public String name;
        public String server;
        public String dimension = "unknown";

        public int firstX, firstY, firstZ;
        public int lastX, lastY, lastZ;

        public int logoutX, logoutY, logoutZ;
        public String logoutDimension = "unknown";
        public long logoutTime;
        public long lastLogin;

        public long firstSeen;
        public long lastSeen;
        public int sightings;


        public int sessionsSeen;
        public boolean notifiedNearby;

        public Entry(UUID uuid, String name, String server) {
            this.uuid = uuid;
            this.name = name;
            this.server = server;
            this.firstSeen = java.lang.System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
        }

        public boolean hasLogoutSpot() {
            return logoutTime > 0;
        }

        public int logoutDistanceTo(int x, int y, int z) {
            double dx = logoutX - x;
            double dy = logoutY - y;
            double dz = logoutZ - z;
            return (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String logoutCoords() {
            return "%d, %d, %d".formatted(logoutX, logoutY, logoutZ);
        }

        public String firstCoords() {
            return "%d, %d, %d".formatted(firstX, firstY, firstZ);
        }

        public String lastCoords() {
            return "%d, %d, %d".formatted(lastX, lastY, lastZ);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();

            tag.putString("uuid", uuid.toString());
            tag.putString("name", name);
            tag.putString("server", server);
            tag.putString("dimension", dimension);

            tag.putIntArray("first", new int[] {firstX, firstY, firstZ});
            tag.putIntArray("last", new int[] {lastX, lastY, lastZ});
            tag.putIntArray("logout", new int[] {logoutX, logoutY, logoutZ});

            tag.putString("logoutDimension", logoutDimension);
            tag.putLong("logoutTime", logoutTime);
            tag.putLong("lastLogin", lastLogin);

            tag.putLong("firstSeen", firstSeen);
            tag.putLong("lastSeen", lastSeen);
            tag.putInt("sightings", sightings);
            tag.putInt("sessionsSeen", sessionsSeen);

            return tag;
        }

        public static Entry fromTag(NbtCompound tag) {
            String rawUuid = tag.getString("uuid", "");
            if (rawUuid.isBlank()) return null;

            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException e) {
                return null;
            }

            Entry entry = new Entry(uuid, tag.getString("name", "unknown"), tag.getString("server", "unknown"));

            entry.dimension = tag.getString("dimension", "unknown");
            entry.logoutDimension = tag.getString("logoutDimension", "unknown");

            int[] first = tag.getIntArray("first").orElse(new int[0]);
            int[] last = tag.getIntArray("last").orElse(new int[0]);
            int[] logout = tag.getIntArray("logout").orElse(new int[0]);

            if (first.length == 3) {
                entry.firstX = first[0];
                entry.firstY = first[1];
                entry.firstZ = first[2];
            }

            if (last.length == 3) {
                entry.lastX = last[0];
                entry.lastY = last[1];
                entry.lastZ = last[2];
            }

            if (logout.length == 3) {
                entry.logoutX = logout[0];
                entry.logoutY = logout[1];
                entry.logoutZ = logout[2];
            }

            entry.logoutTime = tag.getLong("logoutTime", 0);
            entry.lastLogin = tag.getLong("lastLogin", 0);
            entry.firstSeen = tag.getLong("firstSeen", entry.firstSeen);
            entry.lastSeen = tag.getLong("lastSeen", entry.lastSeen);
            entry.sightings = tag.getInt("sightings", 0);
            entry.sessionsSeen = tag.getInt("sessionsSeen", 0);

            return entry;
        }
    }
}
