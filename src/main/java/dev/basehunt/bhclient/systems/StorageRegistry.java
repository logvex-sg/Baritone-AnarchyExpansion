package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.RecordCodec;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent registry of storage containers the player has marked.
 *
 * <p>This is the "where does my stuff live" database behind the storage network: each container records its
 * position, type, the categories it accepts, its network and priority, and the last contents that were
 * actually observed. Everything is keyed per server <em>and</em> dimension, so an overworld chest and a
 * nether chest at the same coordinates are never confused, and a container is only ever treated as a routing
 * destination when it is genuinely reachable.
 *
 * <p>Last-observed contents are stored as an observation with a timestamp, never as a promise: the search and
 * restock code reports them as "last seen", and a container whose chunk is not loaded is skipped rather than
 * assumed to still hold what it held last session.
 *
 * <p>Reading is defensive through {@link RecordCodec}, like every other store, so one corrupt container
 * cannot cost the user the rest of the registry.
 */
public class StorageRegistry extends System<StorageRegistry> {
    private static final int DB_VERSION = 1;

    private final List<Container> containers = new ArrayList<>();
    private int nextId = 1;
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public StorageRegistry() {
        super("base-hunting-storage");
    }

    public static StorageRegistry get() {
        return Systems.get(StorageRegistry.class);
    }

    public List<Container> all() {
        return containers;
    }

    public List<Container> forCurrentServer() {
        String server = ServerUtils.serverAddress();
        List<Container> result = new ArrayList<>();
        for (Container container : containers) if (container.server.equals(server)) result.add(container);
        return result;
    }

    public List<Container> forCurrentLocation() {
        String server = ServerUtils.serverAddress();
        String dimension = ServerUtils.dimension();
        List<Container> result = new ArrayList<>();
        for (Container container : containers) {
            if (container.server.equals(server) && container.dimension.equals(dimension)) result.add(container);
        }
        return result;
    }

    public Container byId(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    public Container at(BlockPos pos) {
        String server = ServerUtils.serverAddress();
        String dimension = ServerUtils.dimension();
        for (Container container : containers) {
            if (container.server.equals(server) && container.dimension.equals(dimension) && container.pos.equals(pos)) {
                return container;
            }
        }
        return null;
    }

    /** Registers a container, or refreshes the timestamp of one already at that position. */
    public Container register(BlockPos pos, String type, int rows) {
        Container existing = at(pos);
        if (existing != null) {
            existing.lastSeen = java.lang.System.currentTimeMillis();
            if (type != null && !type.isBlank()) existing.type = type;
            if (rows > 0) existing.rows = rows;
            save();
            return existing;
        }

        Container container = new Container(nextId++, ServerUtils.serverAddress(), ServerUtils.dimension(), pos);
        container.type = type == null ? "container" : type;
        container.rows = rows;
        containers.add(container);
        save();
        return container;
    }

    /** Removes a container by id. Returns true when one was removed. */
    public boolean unregister(int id) {
        boolean removed = containers.removeIf(container -> container.id == id);
        if (removed) save();
        return removed;
    }

    public int removeWhere(java.util.function.Predicate<Container> predicate) {
        int before = containers.size();
        containers.removeIf(predicate);
        int removed = before - containers.size();
        if (removed > 0) save();
        return removed;
    }

    public void clearCurrentServer() {
        String server = ServerUtils.serverAddress();
        removeWhere(container -> container.server.equals(server));
    }

    /** Distinct network names across the current server, defaulting to at least "default". */
    public List<String> networks() {
        Set<String> names = new LinkedHashSet<>();
        names.add("default");
        for (Container container : forCurrentServer()) names.add(container.network);
        return new ArrayList<>(names);
    }

    public List<Container> inNetwork(String network) {
        List<Container> result = new ArrayList<>();
        for (Container container : forCurrentServer()) {
            if (container.network.equalsIgnoreCase(network)) result.add(container);
        }
        result.sort(Comparator.comparingInt((Container c) -> c.priority));
        return result;
    }

    /** Containers on the current server that accept a category, ordered by priority. */
    public List<Container> accepting(StorageCategory category) {
        List<Container> result = new ArrayList<>();
        for (Container container : forCurrentServer()) {
            if (container.accepts(category)) result.add(container);
        }
        result.sort(Comparator.comparingInt((Container c) -> c.priority));
        return result;
    }

    /** Every container on the current server whose last observation held the item. */
    public List<Container> holding(String itemId) {
        List<Container> result = new ArrayList<>();
        for (Container container : forCurrentServer()) {
            if (container.countOf(itemId) > 0) result.add(container);
        }
        return result;
    }

    /** Records the contents observed in an open container. */
    public void recordContents(Container container, List<Observed> contents, int freeSlots) {
        if (container == null) return;
        container.lastContents = new ArrayList<>(contents);
        container.freeSlots = freeSlots;
        container.verified = true;
        container.lastInteraction = java.lang.System.currentTimeMillis();
        container.failures = 0;
        save();
    }

    public void recordFailure(Container container) {
        if (container == null) return;
        container.failures++;
        save();
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);
        tag.putInt("nextId", nextId);

        NbtList list = new NbtList();
        for (Container container : containers) list.add(container.toTag());
        tag.put("containers", list);

        return tag;
    }

    @Override
    public StorageRegistry fromTag(NbtCompound tag) {
        containers.clear();

        var result = RecordCodec.decodeList(tag, "containers", Container::fromTag);
        containers.addAll(result.records());

        nextId = Math.max(1, tag.getInt("nextId", 1));
        for (Container container : containers) nextId = Math.max(nextId, container.id + 1);

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);

        if (result.anySkipped()) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable storage container record(s) while loading.", result.skipped());
        }

        return this;
    }

    public int lastSkippedRecords() {
        return lastSkippedRecords;
    }

    public int lastLoadedVersion() {
        return lastLoadedVersion;
    }

    /** A stack observed inside a container. */
    public record Observed(String itemId, String name, int count) {
    }

    /** A registered storage container. */
    public static class Container {
        public int id;
        public String server;
        public String dimension;
        public BlockPos pos;
        public String type = "container";
        public String network = "default";
        public String label = "";
        public int priority = 10;
        public int rows;
        public long lastSeen = java.lang.System.currentTimeMillis();
        public long lastInteraction;
        public int failures;
        public boolean verified;
        public int freeSlots = -1;
        public List<Observed> lastContents = new ArrayList<>();
        public Set<StorageCategory> categories = new LinkedHashSet<>();

        public Container(int id, String server, String dimension, BlockPos pos) {
            this.id = id;
            this.server = server;
            this.dimension = dimension;
            this.pos = pos;
        }

        public boolean accepts(StorageCategory category) {
            return categories.contains(category) || categories.contains(StorageCategory.MISC);
        }

        public int countOf(String itemId) {
            int total = 0;
            for (Observed observed : lastContents) {
                if (observed.itemId().equals(itemId)) total += observed.count();
            }
            return total;
        }

        /** Contents from the last observation, or an empty list when the container was never opened. */
        public List<Observed> contents() {
            return lastContents;
        }

        public String describe() {
            String name = label.isBlank() ? type : label;
            return "#%d %s @ %s (%s)".formatted(id, name, pos.toShortString(), dimension);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putInt("id", id);
            tag.putString("server", server);
            tag.putString("dimension", dimension);
            tag.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            tag.putString("type", type);
            tag.putString("network", network);
            tag.putString("label", label);
            tag.putInt("priority", priority);
            tag.putInt("rows", rows);
            tag.putLong("lastSeen", lastSeen);
            tag.putLong("lastInteraction", lastInteraction);
            tag.putInt("failures", failures);
            tag.putBoolean("verified", verified);
            tag.putInt("freeSlots", freeSlots);

            NbtList categoryList = new NbtList();
            for (StorageCategory category : categories) categoryList.add(NbtString.of(category.name()));
            tag.put("categories", categoryList);

            NbtList contentList = new NbtList();
            for (Observed observed : lastContents) {
                NbtCompound entry = new NbtCompound();
                entry.putString("item", observed.itemId());
                entry.putString("name", observed.name());
                entry.putInt("count", observed.count());
                contentList.add(entry);
            }
            tag.put("contents", contentList);

            return tag;
        }

        public static Container fromTag(NbtCompound tag) {
            int[] pos = tag.getIntArray("pos").orElse(null);
            if (pos == null || pos.length != 3) return null;

            String server = tag.getString("server", "");
            if (server.isEmpty()) return null;

            Container container = new Container(tag.getInt("id", 0),
                server, tag.getString("dimension", "unknown"),
                new BlockPos(pos[0], pos[1], pos[2]));

            container.type = tag.getString("type", "container");
            container.network = tag.getString("network", "default");
            container.label = tag.getString("label", "");
            container.priority = tag.getInt("priority", 10);
            container.rows = tag.getInt("rows", 0);
            container.lastSeen = tag.getLong("lastSeen", container.lastSeen);
            container.lastInteraction = tag.getLong("lastInteraction", 0);
            container.failures = tag.getInt("failures", 0);
            container.verified = tag.getBoolean("verified", false);
            container.freeSlots = tag.getInt("freeSlots", -1);

            for (var element : tag.getListOrEmpty("categories")) {
                if (element instanceof NbtString name) {
                    try {
                        container.categories.add(StorageCategory.valueOf(name.asString().orElse("")));
                    } catch (IllegalArgumentException ignored) {
                        // An unknown category name from a newer build is skipped rather than failing the load.
                    }
                }
            }

            for (var element : tag.getListOrEmpty("contents")) {
                if (!(element instanceof NbtCompound entry)) continue;
                String item = entry.getString("item", "");
                if (item.isEmpty()) continue;
                container.lastContents.add(new Observed(item,
                    entry.getString("name", item), entry.getInt("count", 0)));
            }

            return container;
        }
    }
}
