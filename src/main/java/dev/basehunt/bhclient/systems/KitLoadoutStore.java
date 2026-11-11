package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.RecordCodec;
import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.core.sixbt.KitLoadoutRegistry;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent 6b6t kit loadouts, per server.
 *
 * <p>A loadout is what the delivery bot carries for a kit - {@code pvp}, {@code travel} and so on. It is edited
 * in the kit-maker menu and must survive a restart, because a bot that forgets its loadout on relog is useless.
 * Records are keyed per server, matching every other store in the addon, and decoded defensively so one
 * malformed line cannot take the file down.
 */
public class KitLoadoutStore extends System<KitLoadoutStore> {
    private static final int DB_VERSION = 1;

    private final List<Record> records = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public KitLoadoutStore() {
        super("base-hunting-kit-loadouts");
    }

    public static KitLoadoutStore get() {
        return Systems.get(KitLoadoutStore.class);
    }

    /** Builds a live registry for a server, seeding the built-in loadouts on first use. */
    public KitLoadoutRegistry registryFor(String server) {
        KitLoadoutRegistry registry = new KitLoadoutRegistry();

        boolean any = false;
        for (Record record : records) {
            if (!record.server.equals(server)) continue;
            registry.put(record.loadout());
            any = true;
        }

        if (!any) {
            KitLoadoutRegistry defaults = KitLoadoutRegistry.defaults();
            for (KitLoadout loadout : defaults.all()) registry.put(loadout);
        }
        return registry;
    }

    /** Stores a loadout for a server, replacing any earlier one with the same name. */
    public void put(String server, KitLoadout loadout) {
        if (loadout == null) return;
        for (Record record : records) {
            if (record.server.equals(server) && record.name.equalsIgnoreCase(loadout.name())) {
                record.name = loadout.name();
                record.items = encode(loadout);
                save();
                return;
            }
        }
        records.add(new Record(server, loadout.name(), encode(loadout)));
        save();
    }

    /** Removes a loadout by name. Returns true when one was removed. */
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

    private static List<String> encode(KitLoadout loadout) {
        List<String> lines = new ArrayList<>();
        for (KitLoadout.ItemSpec spec : loadout.items()) lines.add(spec.itemId() + " " + spec.count());
        return lines;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        NbtList list = new NbtList();
        for (Record record : records) list.add(record.toTag());
        tag.put("loadouts", list);
        return tag;
    }

    @Override
    public KitLoadoutStore fromTag(NbtCompound tag) {
        records.clear();

        var result = RecordCodec.decodeList(tag, "loadouts", Record::fromTag);
        records.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);
        return this;
    }

    /** One stored loadout: a server, a name and its {@code id count} lines. */
    public static class Record {
        public String server;
        public String name;
        public List<String> items;

        public Record(String server, String name, List<String> items) {
            this.server = server;
            this.name = name;
            this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        }

        public KitLoadout loadout() {
            List<KitLoadout.ItemSpec> specs = new ArrayList<>();
            for (String line : items) {
                KitLoadout.ItemSpec spec = KitLoadout.parseSpec(line);
                if (spec != null) specs.add(spec);
            }
            return KitLoadout.of(name, specs);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("server", server);
            tag.putString("name", name);

            NbtList list = new NbtList();
            for (String line : items) {
                NbtCompound entry = new NbtCompound();
                entry.putString("line", line);
                list.add(entry);
            }
            tag.put("items", list);
            return tag;
        }

        public static Record fromTag(NbtCompound tag) {
            String name = tag.getString("name", "");
            if (name.isBlank()) return null;

            List<String> items = new ArrayList<>();
            NbtList list = tag.getListOrEmpty("items");
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof NbtCompound entry)) continue;
                String line = entry.getString("line", "");
                if (!line.isBlank()) items.add(line);
            }

            return new Record(tag.getString("server", "unknown"), name, items);
        }
    }
}
