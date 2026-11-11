package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.systems.System;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Persistent record of every stash, base and new-chunk cluster the client has seen.
 *
 * <p>Entries are keyed by server so travelling between anarchy servers does not mix their coordinates up.
 * The store is deliberately append-mostly: a stash that is seen again is merged and its counters refreshed
 * rather than duplicated.
 */
public class StashManager extends System<StashManager> {
    private static final int DB_VERSION = 1;

    private final List<Stash> stashes = new ArrayList<>();
    /** Records rejected by the last load, so a partly corrupt file can be reported rather than silently lost. */
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public StashManager() {
        super("base-hunting-stashes");
    }

    public static StashManager get() {
        return meteordevelopment.meteorclient.systems.Systems.get(StashManager.class);
    }

    public List<Stash> getAll() {
        return stashes;
    }

    public List<Stash> forCurrentServer() {
        String server = ServerUtils.serverAddress();
        List<Stash> result = new ArrayList<>();

        for (Stash stash : stashes) {
            if (stash.server.equals(server)) result.add(stash);
        }

        result.sort(Comparator.comparingInt((Stash s) -> -s.score()));
        return result;
    }

    /** Adds the stash or merges it into the closest existing entry. Returns the stored instance. */
    public Stash addOrMerge(Stash stash, int mergeRadius) {
        Stash existing = findNearby(stash.server, stash.pos, mergeRadius);

        if (existing != null) {
            existing.merge(stash);
            save();
            return existing;
        }

        stashes.add(stash);
        save();
        return stash;
    }

    public Stash findNearby(String server, BlockPos pos, int radius) {
        Stash best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Stash stash : stashes) {
            if (!stash.server.equals(server)) continue;

            double distance = stash.pos.getSquaredDistance(pos);
            if (distance <= (double) radius * radius && distance < bestDistance) {
                best = stash;
                bestDistance = distance;
            }
        }

        return best;
    }

    public int removeWhere(java.util.function.Predicate<Stash> predicate) {
        int before = stashes.size();
        stashes.removeIf(predicate);
        int removed = before - stashes.size();

        if (removed > 0) save();
        return removed;
    }

    public void clearCurrentServer() {
        String server = ServerUtils.serverAddress();
        removeWhere(stash -> stash.server.equals(server));
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        NbtList list = new NbtList();
        for (Stash stash : stashes) list.add(stash.toTag());
        tag.put("stashes", list);

        return tag;
    }

    @Override
    public StashManager fromTag(NbtCompound tag) {
        stashes.clear();

        // A single unreadable record is skipped, never fatal: one corrupt entry must not cost the user the
        // rest of the database. The count is kept so the GUI and the status command can report it.
        var result = dev.basehunt.bhclient.core.logic.RecordCodec.decodeList(tag, "stashes", Stash::fromTag);
        stashes.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = dev.basehunt.bhclient.core.logic.RecordCodec.readVersion(tag, DB_VERSION);

        if (result.anySkipped()) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable stash record(s) while loading.", result.skipped());
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

    public enum Kind {
        Stash,
        Base,
        NewChunks,
        Manual
    }

    public static class Stash {
        public String server;
        public BlockPos pos;
        public Kind kind;
        public String dimension;

        public int chests;
        public int barrels;
        public int shulkers;
        public int enderChests;
        public int furnaces;
        public int hoppers;
        public int dispensers;
        public int spawners;
        public int beaconLike;
        public int suspiciousPatterns;

        /** Confidence bucket from {@link dev.basehunt.bhclient.core.logic.StashClassification}. */
        public String classification = "unconfirmed";
        /** How the location was discovered: chunk scan, new chunks, manual entry. */
        public String discoveryMethod = "scan";
        /** Weighted confidence score, cached so it survives a restart. */
        public int confidence;

        public long firstSeen;
        public long lastSeen;

        public Stash(String server, BlockPos pos, Kind kind) {
            this.server = server;
            this.pos = pos;
            this.kind = kind;
            this.dimension = ServerUtils.dimension();
            this.firstSeen = java.lang.System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
        }

        public int totalStorage() {
            return chests + barrels + shulkers + enderChests + furnaces + hoppers + dispensers;
        }

        /** Weighted confidence score, delegated to the shared scorer so the module and store agree. */
        public int score() {
            return dev.basehunt.bhclient.core.logic.StashScorer.score(evidence());
        }

        /** Builds the scorer's evidence view of this stash. */
        public dev.basehunt.bhclient.core.logic.StashScorer.Evidence evidence() {
            return new dev.basehunt.bhclient.core.logic.StashScorer.Evidence(
                chests, 0, barrels, shulkers, enderChests, furnaces, hoppers, dispensers, spawners,
                beaconLike, suspiciousPatterns);
        }

        /** Recomputes and stores the classification and confidence from the current counters. */
        public void refreshClassification() {
            int score = score();
            confidence = score;
            classification = dev.basehunt.bhclient.core.logic.StashScorer
                .classify(score, totalStorage()).describe();
        }

        public void merge(Stash other) {
            // Counters are peaks, not sums: the same chunk is re-sent on every re-visit.
            chests = Math.max(chests, other.chests);
            barrels = Math.max(barrels, other.barrels);
            shulkers = Math.max(shulkers, other.shulkers);
            enderChests = Math.max(enderChests, other.enderChests);
            furnaces = Math.max(furnaces, other.furnaces);
            hoppers = Math.max(hoppers, other.hoppers);
            dispensers = Math.max(dispensers, other.dispensers);
            spawners = Math.max(spawners, other.spawners);
            beaconLike = Math.max(beaconLike, other.beaconLike);
            suspiciousPatterns = Math.max(suspiciousPatterns, other.suspiciousPatterns);

            lastSeen = java.lang.System.currentTimeMillis();
            dimension = other.dimension;
            refreshClassification();
        }

        public String describeCounts() {
            StringBuilder builder = new StringBuilder();

            appendCount(builder, "chest", chests);
            appendCount(builder, "barrel", barrels);
            appendCount(builder, "shulker", shulkers);
            appendCount(builder, "ender chest", enderChests);
            appendCount(builder, "furnace", furnaces);
            appendCount(builder, "hopper", hoppers);
            appendCount(builder, "dispenser", dispensers);
            appendCount(builder, "spawner", spawners);
            appendCount(builder, "beacon", beaconLike);

            return builder.isEmpty() ? "none" : builder.toString();
        }

        private static void appendCount(StringBuilder builder, String label, int count) {
            if (count <= 0) return;
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(count).append(' ').append(label).append(count == 1 ? "" : "s");
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();

            tag.putString("server", server);
            tag.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            tag.putString("kind", kind.name());
            tag.putString("dimension", dimension);

            tag.putInt("chests", chests);
            tag.putInt("barrels", barrels);
            tag.putInt("shulkers", shulkers);
            tag.putInt("enderChests", enderChests);
            tag.putInt("furnaces", furnaces);
            tag.putInt("hoppers", hoppers);
            tag.putInt("dispensers", dispensers);
            tag.putInt("spawners", spawners);
            tag.putInt("beaconLike", beaconLike);
            tag.putInt("suspiciousPatterns", suspiciousPatterns);

            tag.putLong("firstSeen", firstSeen);
            tag.putLong("lastSeen", lastSeen);

            tag.putString("classification", classification);
            tag.putString("discoveryMethod", discoveryMethod);
            tag.putInt("confidence", confidence);

            return tag;
        }

        public static Stash fromTag(NbtCompound tag) {
            int[] pos = tag.getIntArray("pos").orElse(null);
            if (pos == null || pos.length != 3) return null;

            Kind kind;
            try {
                kind = Kind.valueOf(tag.getString("kind", "Stash"));
            } catch (IllegalArgumentException e) {
                kind = Kind.Stash;
            }

            Stash stash = new Stash(tag.getString("server", "unknown"), new BlockPos(pos[0], pos[1], pos[2]), kind);

            stash.dimension = tag.getString("dimension", "unknown");
            stash.chests = tag.getInt("chests", 0);
            stash.barrels = tag.getInt("barrels", 0);
            stash.shulkers = tag.getInt("shulkers", 0);
            stash.enderChests = tag.getInt("enderChests", 0);
            stash.furnaces = tag.getInt("furnaces", 0);
            stash.hoppers = tag.getInt("hoppers", 0);
            stash.dispensers = tag.getInt("dispensers", 0);
            stash.spawners = tag.getInt("spawners", 0);
            stash.beaconLike = tag.getInt("beaconLike", 0);
            stash.suspiciousPatterns = tag.getInt("suspiciousPatterns", 0);
            stash.firstSeen = tag.getLong("firstSeen", stash.firstSeen);
            stash.lastSeen = tag.getLong("lastSeen", stash.lastSeen);

            stash.discoveryMethod = tag.getString("discoveryMethod", "scan");
            // Older saves have no classification; derive it rather than leaving the field blank.
            String stored = tag.getString("classification", "");
            if (stored.isEmpty()) stash.refreshClassification();
            else {
                stash.classification = stored;
                stash.confidence = tag.getInt("confidence", stash.score());
            }

            return stash;
        }
    }

    /** Convenience for callers that want to mutate a list of stashes without touching the store. */
    public void forEachCurrentServer(Consumer<Stash> consumer) {
        for (Stash stash : forCurrentServer()) consumer.accept(stash);
    }
}
