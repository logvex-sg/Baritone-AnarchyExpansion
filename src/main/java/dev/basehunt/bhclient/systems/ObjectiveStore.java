package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.ObjectivePlanner;
import dev.basehunt.bhclient.core.logic.RecordCodec;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.List;

/**
 * Persistent objective queue.
 *
 * <p>A to-do list is only useful if it survives the session: the shulker you meant to pick up and the base
 * of the player who logged out are exactly the kind of thing you come back for after a restart. Records go
 * through {@link RecordCodec} so one unreadable entry is skipped rather than losing the whole list, and the
 * in-memory list is bounded by {@link ObjectivePlanner} so the file cannot grow without limit.
 */
public class ObjectiveStore extends System<ObjectiveStore> {
    private static final int DB_VERSION = 1;
    private static final int CAP = 64;

    private final ObjectivePlanner planner = new ObjectivePlanner(CAP);
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public ObjectiveStore() {
        super("base-hunting-objectives");
    }

    public static ObjectiveStore get() {
        return Systems.get(ObjectiveStore.class);
    }

    public ObjectivePlanner planner() {
        return planner;
    }

    /** Adds an objective and persists it. */
    public ObjectivePlanner.Objective add(ObjectivePlanner.Kind kind, String label, int x, int y, int z,
                                          int mergeRadius) {
        ObjectivePlanner.Objective objective = planner.add(kind, label, x, y, z,
            java.lang.System.currentTimeMillis(), mergeRadius);
        save();
        return objective;
    }

    /** Marks an objective complete and persists the change. */
    public boolean complete(long id) {
        boolean changed = planner.complete(id);
        if (changed) save();
        return changed;
    }

    public int removeWhere(java.util.function.Predicate<ObjectivePlanner.Objective> predicate) {
        int removed = planner.removeWhere(predicate);
        if (removed > 0) save();
        return removed;
    }

    public void clearAll() {
        planner.clear();
        save();
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
        for (ObjectivePlanner.Objective objective : planner.all()) list.add(toTag(objective));
        tag.put("objectives", list);
        return tag;
    }

    @Override
    public ObjectiveStore fromTag(NbtCompound tag) {
        var decoded = RecordCodec.decodeList(tag, "objectives", this::decode);
        planner.replaceAll(decoded.records());

        lastSkippedRecords = decoded.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);

        if (decoded.anySkipped()) {
            dev.basehunt.bhclient.BaseHuntingAddon.LOG.warn(
                "Skipped {} unreadable objective record(s) while loading.", decoded.skipped());
        }

        return this;
    }

    private NbtCompound toTag(ObjectivePlanner.Objective objective) {
        NbtCompound tag = new NbtCompound();
        tag.putLong("id", objective.id());
        tag.putString("kind", objective.kind().name());
        tag.putString("label", objective.label());
        tag.putIntArray("pos", new int[] {objective.x(), objective.y(), objective.z()});
        tag.putLong("created", objective.created());
        return tag;
    }

    private ObjectivePlanner.Objective decode(NbtCompound tag) {
        int[] pos = tag.getIntArray("pos").orElse(null);
        if (pos == null || pos.length != 3) return null;

        ObjectivePlanner.Kind kind;
        try {
            kind = ObjectivePlanner.Kind.valueOf(tag.getString("kind", "CUSTOM"));
        } catch (IllegalArgumentException e) {
            kind = ObjectivePlanner.Kind.CUSTOM;
        }

        return new ObjectivePlanner.Objective(
            tag.getLong("id", 0),
            kind,
            tag.getString("label", ""),
            pos[0], pos[1], pos[2],
            tag.getLong("created", 0)
        );
    }
}
