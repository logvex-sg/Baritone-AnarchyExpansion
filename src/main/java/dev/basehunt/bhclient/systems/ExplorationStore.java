package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.ExploredRegions;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent exploration memory: which chunks have been covered and which custom waypoints the user set.
 *
 * <p>This is what stops the client re-flying over empty territory it already crossed. Visited chunks are
 * stored as packed longs so the on-disk form stays small even after a very long session, and the in-memory
 * set is bounded by {@link ExploredRegions} so memory cannot grow without limit.
 */
public class ExplorationStore extends System<ExplorationStore> {
    private static final int DB_VERSION = 1;

    private final ExploredRegions regions = new ExploredRegions();
    private final List<Waypoint> customWaypoints = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public ExplorationStore() {
        super("base-hunting-exploration");
    }

    public static ExplorationStore get() {
        return Systems.get(ExplorationStore.class);
    }

    public ExploredRegions regions() {
        return regions;
    }

    public List<Waypoint> customWaypoints() {
        return customWaypoints;
    }

    public void addWaypoint(String label, BlockPos pos, String dimension) {
        customWaypoints.add(new Waypoint(label, pos, dimension));
        save();
    }

    public boolean removeWaypoint(int index) {
        if (index < 0 || index >= customWaypoints.size()) return false;
        customWaypoints.remove(index);
        save();
        return true;
    }

    public void clearWaypoints() {
        customWaypoints.clear();
        save();
    }

    public void clearExplored() {
        regions.clear();
        save();
    }

    /** Marks a block position's chunk as explored. */
    public void markExplored(BlockPos pos) {
        regions.markExplored(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        // Packed chunk keys as a long array; the smallest representation NBT offers for this shape.
        long[] explored = new long[regions.exploredCount()];
        int i = 0;
        for (long key : regions.exploredKeys()) explored[i++] = key;
        tag.putLongArray("explored", explored);

        long[] review = new long[regions.reviewCount()];
        i = 0;
        for (long key : regions.reviewKeys()) review[i++] = key;
        tag.putLongArray("review", review);

        NbtList waypoints = new NbtList();
        for (Waypoint waypoint : customWaypoints) waypoints.add(waypoint.toTag());
        tag.put("waypoints", waypoints);

        return tag;
    }

    @Override
    public ExplorationStore fromTag(NbtCompound tag) {
        regions.clear();
        customWaypoints.clear();

        // Packed chunk keys are read as a whole array: a truncated array still yields every key that is
        // present, and a missing array yields none, so neither case can abort the load.
        long[] explored = tag.getLongArray("explored").orElse(new long[0]);
        for (long key : explored) {
            regions.markExplored(ExploredRegions.keyX(key), ExploredRegions.keyZ(key));
        }

        long[] review = tag.getLongArray("review").orElse(new long[0]);
        for (long key : review) {
            regions.markRequiresReview(ExploredRegions.keyX(key), ExploredRegions.keyZ(key));
        }

        // Waypoints go through the shared tolerant decoder: one malformed waypoint must not prevent the rest
        // of the store loading.
        var decoded = dev.basehunt.bhclient.core.logic.RecordCodec.decodeList(tag, "waypoints", Waypoint::fromTag);
        customWaypoints.addAll(decoded.records());

        lastSkippedRecords = decoded.skipped();
        lastLoadedVersion = dev.basehunt.bhclient.core.logic.RecordCodec.readVersion(tag, DB_VERSION);

        if (decoded.anySkipped()) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable waypoint record(s) while loading.", decoded.skipped());
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

    public static class Waypoint {
        public String label;
        public BlockPos pos;
        public String dimension;

        public Waypoint(String label, BlockPos pos, String dimension) {
            this.label = label;
            this.pos = pos;
            this.dimension = dimension;
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("label", label);
            tag.putString("dimension", dimension);
            tag.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            return tag;
        }

        public static Waypoint fromTag(NbtCompound tag) {
            int[] pos = tag.getIntArray("pos").orElse(null);
            if (pos == null || pos.length != 3) return null;
            return new Waypoint(tag.getString("label", "waypoint"),
                new BlockPos(pos[0], pos[1], pos[2]), tag.getString("dimension", "unknown"));
        }
    }
}
