package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.kit.Kit;
import dev.basehunt.bhclient.core.kit.KitRegistry;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent kit definitions and their local cooldown state, per server.
 *
 * <p>The server owns the real cooldown; this only remembers what the client last saw, so a restart does not
 * make the client spam a kit command the server will reject. Records are keyed per server, because two servers
 * rarely share a kit layout and a cooldown from one must not suppress a claim on another.
 *
 * <p>Loading is defensive through {@code RecordCodec}: a single malformed kit is skipped rather than aborting
 * the whole file, matching the other stores.
 */
public class KitBotStore extends System<KitBotStore> {
    private static final int DB_VERSION = 1;

    private final List<Record> records = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public KitBotStore() {
        super("base-hunting-kits");
    }

    public static KitBotStore get() {
        return Systems.get(KitBotStore.class);
    }

    /** Builds a live registry for a server, seeding the built-in kits on first use. */
    public KitRegistry registryFor(String server) {
        KitRegistry registry = new KitRegistry();

        boolean any = false;
        for (Record record : records) {
            if (!record.server.equals(server)) continue;
            registry.register(record.kit());
            if (record.lastClaim > 0) {
                // Replay the last-claim time so the cooldown survives a restart.
                registry.recordClaim(record.kit().name(), record.lastClaim);
            }
            any = true;
        }

        if (!any) registry.registerAll(defaultKits());
        return registry;
    }

    /** The kits a fresh server starts with. A server-specific kit can be added with {@code /bh kit add}. */
    public static List<Kit> defaultKits() {
        return List.of(
            Kit.of("travel", "/kit travel", 3600),
            Kit.of("pvp", "/kit pvp", 3600),
            Kit.of("build", "/kit build", 3600),
            Kit.of("starter", "/kit starter", 3600)
        );
    }

    /** Records or updates a kit for a server. */
    public Record put(String server, Kit kit) {
        for (Record record : records) {
            if (record.server.equals(server) && record.kit().key().equals(kit.key())) {
                record.name = kit.name();
                record.command = kit.command();
                record.cooldownSeconds = kit.cooldownSeconds();
                save();
                return record;
            }
        }
        Record record = new Record(server, kit);
        records.add(record);
        save();
        return record;
    }

    /** Records a successful claim time for a kit. */
    public void recordClaim(String server, String kitName, long when) {
        for (Record record : records) {
            if (record.server.equals(server) && record.kit().key().equalsIgnoreCase(kitName)) {
                record.lastClaim = when;
                record.claims++;
                save();
                return;
            }
        }
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
        tag.put("kits", list);
        return tag;
    }

    @Override
    public KitBotStore fromTag(NbtCompound tag) {
        records.clear();

        var result = dev.basehunt.bhclient.core.logic.RecordCodec.decodeList(tag, "kits", Record::fromTag);
        records.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = dev.basehunt.bhclient.core.logic.RecordCodec.readVersion(tag, DB_VERSION);
        return this;
    }

    /** One stored kit plus its claim bookkeeping. */
    public static class Record {
        public String server;
        public String name;
        public String command;
        public int cooldownSeconds;
        public long lastClaim;
        public int claims;

        public Record(String server, Kit kit) {
            this.server = server;
            this.name = kit.name();
            this.command = kit.command();
            this.cooldownSeconds = kit.cooldownSeconds();
        }

        public Kit kit() {
            return Kit.of(name, command, cooldownSeconds);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("server", server);
            tag.putString("name", name);
            tag.putString("command", command);
            tag.putInt("cooldown", cooldownSeconds);
            tag.putLong("lastClaim", lastClaim);
            tag.putInt("claims", claims);
            return tag;
        }

        public static Record fromTag(NbtCompound tag) {
            String name = tag.getString("name", "");
            if (name.isBlank()) return null;

            Record record = new Record(tag.getString("server", "unknown"), Kit.of(name, "", 0));
            record.command = tag.getString("command", "/kit " + name);
            record.cooldownSeconds = tag.getInt("cooldown", 3600);
            record.lastClaim = tag.getLong("lastClaim", 0);
            record.claims = tag.getInt("claims", 0);
            return record;
        }
    }
}
