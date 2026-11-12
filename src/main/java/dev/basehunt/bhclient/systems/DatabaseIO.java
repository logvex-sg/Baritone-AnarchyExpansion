package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.BaseHuntingAddon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.File;
import java.nio.file.Files;

/**
 * Whole-database export and import for the addon's persisted systems.
 *
 * <p>Every store already writes itself atomically, so this class is only about moving a complete snapshot
 * in and out of the game: to the clipboard (Meteor's own convention) or to a single file. Import replaces
 * the current data rather than merging, which makes the operation predictable and reversible: take a backup
 * first, and the exact previous state can be restored by importing it.
 *
 * <p>Import is defensive on purpose. A tag missing a section leaves that store untouched, so an older or
 * partial export can never wipe data it does not describe.
 *
 * <p>Every persisted store must appear in {@link #SECTIONS}; the coverage is asserted by the headless
 * suite so a newly added store cannot silently fall out of the backup path. A store that was missing here
 * would be exported empty and, worse, importing a backup would silently discard its live data.
 */
public final class DatabaseIO {
    private static final String EXPORT_FILE_NAME = "base-hunting-export.nbt";
    private static final int FORMAT_VERSION = dev.basehunt.bhclient.core.logic.DatabaseVersion.CURRENT;

    /**
     * Verdict of the last import attempt, so the caller can report a refusal or an upgrade rather than a bare
     * section count. Starts as {@link dev.basehunt.bhclient.core.logic.DatabaseVersion.Verdict#CURRENT} so a
     * never-imported session reads sane.
     */
    private static volatile dev.basehunt.bhclient.core.logic.DatabaseVersion.Verdict lastVerdict =
        dev.basehunt.bhclient.core.logic.DatabaseVersion.Verdict.CURRENT;

    /**
     * One persisted store: the key it is stored under and how to reach it. Keeping this as data (rather
     * than four parallel put/apply call sites) is what makes it impossible to add a store to one direction
     * and forget the other.
     */
    private record Section(String key, java.util.function.Supplier<meteordevelopment.meteorclient.systems.System<?>> store) {
    }

    private static final java.util.List<Section> SECTIONS = java.util.List.of(
        new Section("stashes", StashManager::get),
        new Section("players", PlayerTracker::get),
        new Section("enderChests", EnderChestStore::get),
        new Section("exploration", ExplorationStore::get),
        new Section("storage", StorageRegistry::get),
        new Section("workflows", WorkflowManager::get),
        new Section("kits", KitBotStore::get),
        new Section("kitLoadouts", KitLoadoutStore::get),
        new Section("sixbtServer", SixbtServerStore::get),
        new Section("sixbtPlayers", SixbtPlayerStore::get)
    );

    private DatabaseIO() {
    }

    /** The keys every export carries; used by the coverage test. */
    public static java.util.List<String> sectionKeys() {
        return SECTIONS.stream().map(Section::key).toList();
    }

    /** Builds one NBT tag holding every store's data. */
    public static NbtCompound exportAll() {
        NbtCompound root = new NbtCompound();
        root.putInt(dev.basehunt.bhclient.core.logic.DatabaseVersion.KEY, FORMAT_VERSION);
        root.putLong("exportedAt", java.lang.System.currentTimeMillis());

        for (Section section : SECTIONS) put(root, section.key(), section.store().get());

        return root;
    }

    private static void put(NbtCompound root, String key, meteordevelopment.meteorclient.systems.System<?> system) {
        if (system == null) return;
        NbtCompound tag = system.toTag();
        if (tag != null) root.put(key, tag);
    }

    /**
     * Applies an exported tag. Stores with no matching section are left as they are, and every store is
     * wrapped so a malformed section cannot abort the whole import.
     *
     * <p>A tag written by a newer build is refused outright (see {@link DatabaseVersion}): applying a format
     * we do not understand would silently drop the fields it introduced. An older tag is applied, and the
     * verdict is remembered so the caller can report that it was upgraded.
     *
     * @return the number of sections applied, or 0 when the import was refused
     */
    public static int importAll(NbtCompound root) {
        if (root == null) {
            lastVerdict = dev.basehunt.bhclient.core.logic.DatabaseVersion.Verdict.CURRENT;
            return 0;
        }

        var verdict = dev.basehunt.bhclient.core.logic.DatabaseVersion.verdictOf(root);
        lastVerdict = verdict;

        if (verdict == dev.basehunt.bhclient.core.logic.DatabaseVersion.Verdict.TOO_NEW) {
            BaseHuntingAddon.LOG.warn("Refused to import a database written by a newer build: {}",
                dev.basehunt.bhclient.core.logic.DatabaseVersion.describe(
                    dev.basehunt.bhclient.core.logic.DatabaseVersion.versionOf(root)));
            return 0;
        }

        int applied = 0;
        for (Section section : SECTIONS) applied += apply(section.key(), root, section.store().get());

        return applied;
    }

    /** Verdict of the last {@link #importAll} / {@link #importFromFile} / {@link #importFromClipboard} call. */
    public static dev.basehunt.bhclient.core.logic.DatabaseVersion.Verdict lastVerdict() {
        return lastVerdict;
    }

    private static int apply(String key, NbtCompound root, meteordevelopment.meteorclient.systems.System<?> system) {
        if (system == null) return 0;
        if (!root.contains(key)) return 0;

        NbtCompound section = root.getCompoundOrEmpty(key);
        if (section.isEmpty()) return 0;

        try {
            system.fromTag(section);
            system.save();
            return 1;
        } catch (Exception e) {
            BaseHuntingAddon.LOG.error("Failed to import the {} section", key, e);
            return 0;
        }
    }

    public static boolean exportToClipboard() {
        return NbtUtils.toClipboard(exportAll());
    }

    public static boolean exportToFile() {
        try {
            File file = exportFile();
            file.getParentFile().mkdirs();
            NbtIo.writeCompressed(exportAll(), file.toPath());
            return true;
        } catch (Exception e) {
            BaseHuntingAddon.LOG.error("Failed to export the database", e);
            return false;
        }
    }

    public static int importFromClipboard() {
        NbtCompound tag = NbtUtils.fromClipboard();
        if (tag == null) return 0;
        return importAll(tag);
    }

    public static int importFromFile() {
        try {
            File file = exportFile();
            if (!file.exists()) return 0;

            NbtCompound tag = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
            if (tag == null) return 0;

            return importAll(tag);
        } catch (Exception e) {
            BaseHuntingAddon.LOG.error("Failed to import the database", e);
            return 0;
        }
    }

    public static File exportFile() {
        return new File(MeteorClient.FOLDER, EXPORT_FILE_NAME);
    }

    public static boolean exportFileExists() {
        try {
            return Files.exists(exportFile().toPath());
        } catch (Exception e) {
            return false;
        }
    }
}
