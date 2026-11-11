package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent record of ender chests the client has opened or seen.
 *
 * <p>Kept per server and dimension so an overworld chest is never confused with a nether one at the same
 * coordinates. Like {@link StashManager}, reading is defensive: a single malformed record is skipped rather
 * than allowed to abort the whole load.
 */
public class EnderChestStore extends System<EnderChestStore> {
    private static final int DB_VERSION = 1;

    private final List<Chest> chests = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public EnderChestStore() {
        super("base-hunting-ender-chests");
    }

    public static EnderChestStore get() {
        return Systems.get(EnderChestStore.class);
    }

    public List<Chest> all() {
        return chests;
    }

    public List<Chest> forCurrentServer() {
        String server = ServerUtils.serverAddress();
        List<Chest> result = new ArrayList<>();
        for (Chest chest : chests) if (chest.server.equals(server)) result.add(chest);
        result.sort(Comparator.comparingLong((Chest c) -> -c.lastSeen));
        return result;
    }

    /** Records a chest, refreshing the timestamp if it is already known. */
    public Chest record(BlockPos pos, String dimension, String server) {
        for (Chest chest : chests) {
            if (chest.server.equals(server) && chest.dimension.equals(dimension) && chest.pos.equals(pos)) {
                chest.lastSeen = java.lang.System.currentTimeMillis();
                chest.timesOpened++;
                save();
                return chest;
            }
        }

        Chest chest = new Chest(server, dimension, pos);
        chests.add(chest);
        save();
        return chest;
    }

    public Chest nearest(String server, String dimension, BlockPos from) {
        Chest best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Chest chest : chests) {
            if (!chest.server.equals(server) || !chest.dimension.equals(dimension)) continue;

            double distance = chest.pos.getSquaredDistance(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chest;
            }
        }

        return best;
    }

    public int removeWhere(java.util.function.Predicate<Chest> predicate) {
        int before = chests.size();
        chests.removeIf(predicate);
        int removed = before - chests.size();
        if (removed > 0) save();
        return removed;
    }

    public void clearCurrentServer() {
        String server = ServerUtils.serverAddress();
        removeWhere(chest -> chest.server.equals(server));
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        NbtList list = new NbtList();
        for (Chest chest : chests) list.add(chest.toTag());
        tag.put("chests", list);

        return tag;
    }

    @Override
    public EnderChestStore fromTag(NbtCompound tag) {
        chests.clear();

        var result = dev.basehunt.bhclient.core.logic.RecordCodec.decodeList(tag, "chests", Chest::fromTag);
        chests.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = dev.basehunt.bhclient.core.logic.RecordCodec.readVersion(tag, DB_VERSION);

        if (result.anySkipped()) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable ender chest record(s) while loading.", result.skipped());
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

    public static class Chest {
        public String server;
        public String dimension;
        public BlockPos pos;
        public long firstSeen;
        public long lastSeen;
        public int timesOpened;

        public Chest(String server, String dimension, BlockPos pos) {
            this.server = server;
            this.dimension = dimension;
            this.pos = pos;
            this.firstSeen = java.lang.System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
            this.timesOpened = 1;
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("server", server);
            tag.putString("dimension", dimension);
            tag.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            tag.putLong("firstSeen", firstSeen);
            tag.putLong("lastSeen", lastSeen);
            tag.putInt("timesOpened", timesOpened);
            return tag;
        }

        public static Chest fromTag(NbtCompound tag) {
            int[] pos = tag.getIntArray("pos").orElse(null);
            if (pos == null || pos.length != 3) return null;

            Chest chest = new Chest(tag.getString("server", "unknown"),
                tag.getString("dimension", "unknown"), new BlockPos(pos[0], pos[1], pos[2]));

            chest.firstSeen = tag.getLong("firstSeen", chest.firstSeen);
            chest.lastSeen = tag.getLong("lastSeen", chest.lastSeen);
            chest.timesOpened = tag.getInt("timesOpened", 1);
            return chest;
        }
    }
}
