package dev.basehunt.bhclient.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.selection.ISelection;
import baritone.api.utils.BlockOptionalMeta;
import dev.basehunt.bhclient.core.logic.ProbeRetry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Thin wrapper around Baritone so every module can stay quiet about whether Baritone is actually present.
 *
 * <p>Baritone is a compile-only dependency. Touching {@link BaritoneAPI} from a class that is loaded when
 * Baritone is missing would throw {@link NoClassDefFoundError}, so every reference lives behind the
 * {@link #AVAILABLE} check and the class is only initialised lazily from {@link #get()}.
 *
 * <p>Class presence is not the same as a usable Baritone. Baritone bundles its elytra pathfinder as a
 * nested jar ({@code nether-pathfinder}); when that nested jar fails to load, {@code BaritoneAPI}'s static
 * initialiser throws and every later call into it throws too. {@link #AVAILABLE} therefore probes with the
 * class <i>not</i> initialised and {@link #usable()} resolves the primary baritone once, caching the
 * outcome and the reason, so a half-broken Baritone degrades to "not usable" instead of crashing the addon.
 */
public final class BaritoneHelper {
    /** True when the Baritone API classes are on the classpath (they may still be unusable). */
    public static final boolean AVAILABLE;

    private static final String PRESENCE_ERROR;

    private static volatile boolean probed;
    private static volatile IBaritone cached;
    private static volatile String probeError = "";

    /** How long a failed probe is trusted before it is retried, in milliseconds. */
    private static final long FAILURE_RETRY_MS = ProbeRetry.DEFAULT_RETRY_MS;
    private static volatile long failedAt;

    static {
        boolean present;
        String reason = "";
        try {
            // initialize=false: only check the class exists. Running BaritoneAPI's static initialiser here
            // would surface a broken nested pathfinder jar as an error we cannot report cleanly.
            Class.forName("baritone.api.BaritoneAPI", false, BaritoneHelper.class.getClassLoader());
            present = true;
        } catch (Throwable t) {
            present = false;
            reason = "baritone.api.BaritoneAPI is not on the classpath";
        }
        AVAILABLE = present;
        PRESENCE_ERROR = reason;
    }

    private BaritoneHelper() {
    }

    /**
     * The primary baritone, or null when Baritone is missing or unusable.
     *
     * <p>Baritone builds its singleton during client initialisation, but this addon can be probed
     * before that has happened — the HUD reads {@link #health()} on the first frame. A failure is
     * therefore only cached for {@link #FAILURE_RETRY_MS}, not for the client's lifetime: latching a
     * single early miss made {@link #usable()} return false forever and every module that gates on it
     * disabled itself on enable. Success is still cached permanently, because a resolved instance
     * stays valid.
     */
    public static IBaritone get() {
        if (!AVAILABLE) return null;

        IBaritone local = cached;
        if (local != null) return local;

        boolean retry = ProbeRetry.shouldProbe(
            probed, cached != null, failedAt, java.lang.System.currentTimeMillis(), FAILURE_RETRY_MS);
        if (!retry) return null;

        synchronized (BaritoneHelper.class) {
            local = cached;
            if (local != null) return local;

            try {
                local = BaritoneAPI.getProvider().getPrimaryBaritone();
                cached = local;
                if (local == null) {
                    probeError = "Baritone has no primary instance";
                    failedAt = java.lang.System.currentTimeMillis();
                } else {
                    probeError = "";
                }
            } catch (Throwable t) {
                cached = null;
                probeError = describe(t);
                failedAt = java.lang.System.currentTimeMillis();
            } finally {
                probed = true;
            }
        }

        return cached;
    }

    /** True when Baritone is installed and its API actually answers. */
    public static boolean usable() {
        return get() != null;
    }

    /**
     * A short reason Baritone is unusable, or an empty string when it is fine.
     *
     * <p>Reads the cached probe result without starting one. The HUD calls this every frame, so probing
     * here would run {@code BaritoneAPI}'s static initialiser on the very first rendered frame — before
     * Baritone has finished initialising — and cache a spurious failure.
     */
    public static String health() {
        if (!AVAILABLE) return PRESENCE_ERROR;
        if (cached != null) return "";
        if (!probed) return "";
        return probeError.isEmpty() ? "Baritone is not responding" : probeError;
    }

    /** The installed Baritone version, or "unknown" when it cannot be read. */
    public static String version() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("baritone-meteor")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static Settings settings() {
        try {
            if (get() == null) return null;
            return BaritoneAPI.getSettings();
        } catch (Throwable t) {
            probeError = describe(t);
            cached = null;
            failedAt = java.lang.System.currentTimeMillis();
            return null;
        }
    }

    public static boolean isPathing() {
        IBaritone baritone = get();
        if (baritone == null) return false;
        try {
            return baritone.getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isElytraLoaded() {
        IBaritone baritone = get();
        if (baritone == null) return false;
        try {
            return baritone.getElytraProcess().isLoaded();
        } catch (Throwable t) {
            // The elytra process is backed by a nested nether-pathfinder jar. When it fails to load every
            // call throws; report "not loaded" so callers retry instead of propagating into the tick loop.
            return false;
        }
    }

    /** True when the elytra process is running but can be interrupted without losing the route. */
    public static boolean isElytraSafeToCancel() {
        IBaritone baritone = get();
        if (baritone == null) return false;
        try {
            return baritone.getElytraProcess().isLoaded() && baritone.getElytraProcess().isSafeToCancel();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when Baritone is actively flying the elytra or walking a path. */
    public static boolean isMoving() {
        return isPathing() || isElytraLoaded();
    }

    public static void stop() {
        IBaritone baritone = get();
        if (baritone == null) return;

        try {
            baritone.getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {
            // A broken behavior must not throw out of a module's onDeactivate.
        }

        try {
            baritone.getElytraProcess().onLostControl();
        } catch (Throwable ignored) {
        }
    }

    /** Flies to a destination using Baritone's nether-pathfinder powered elytra process. */
    public static void elytraPathTo(BlockPos destination) {
        IBaritone baritone = get();
        if (baritone == null) return;

        try {
            baritone.getElytraProcess().pathTo(destination);
        } catch (Throwable t) {
            probeError = describe(t);
            cached = null;
            failedAt = java.lang.System.currentTimeMillis();
        }
    }

    public static void elytraPathTo(Goal goal) {
        IBaritone baritone = get();
        if (baritone == null) return;

        try {
            baritone.getElytraProcess().pathTo(goal);
        } catch (Throwable t) {
            probeError = describe(t);
            cached = null;
            failedAt = java.lang.System.currentTimeMillis();
        }
    }

    public static void walkTo(BlockPos destination) {
        IBaritone baritone = get();
        if (baritone == null) return;

        try {
            baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(destination, 2));
        } catch (Throwable ignored) {
        }
    }

    public static BlockPos currentDestination() {
        IBaritone baritone = get();
        if (baritone == null) return null;

        try {
            return baritone.getElytraProcess().currentDestination();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Forgets the elytra process's cached state, forcing a clean re-plan on the next path request. */
    public static void resetElytraState() {
        IBaritone baritone = get();
        if (baritone == null) return;

        try {
            baritone.getElytraProcess().resetState();
        } catch (Throwable ignored) {
        }
    }

    public static void runCommand(String command) {
        IBaritone baritone = get();
        if (baritone == null) return;

        try {
            baritone.getCommandManager().execute(command);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Runs a Baritone command and reports whether it was accepted. Used by the job layer so a refused
     * command (an unknown selector, a missing schematic) is reported instead of looking like success.
     */
    public static boolean command(String command) {
        IBaritone baritone = get();
        if (baritone == null || command == null || command.isBlank()) return false;

        try {
            return baritone.getCommandManager().execute(command);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Digs a straight tunnel: {@code #tunnel <height>}. Height 0 is a 1x1 tunnel at eye level. */
    public static boolean tunnel(int height) {
        return command("#tunnel " + Math.max(0, height));
    }

    public static String getPrefix() {
        Settings settings = settings();
        return settings == null ? "" : settings.prefix.value;
    }

    // Baritone processes

    /**
     * Mines blocks matching {@code name} until {@code quantity} have been collected.
     *
     * <p>{@code name} is a block or item selector understood by Baritone, e.g. {@code diamond_ore}. A
     * {@code quantity} of 0 mines until the process finds nothing more.
     *
     * @return true when the job was handed to Baritone
     */
    public static boolean mine(String name, int quantity) {
        IBaritone baritone = get();
        if (baritone == null || name == null || name.isBlank()) return false;

        baritone.getMineProcess().mineByName(Math.max(0, quantity), name);
        return true;
    }

    /** Walks (not flies) to an exact block position. */
    public static boolean gotoBlock(int x, int y, int z) {
        IBaritone baritone = get();
        if (baritone == null) return false;

        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
        return true;
    }

    /** Finds and walks to the nearest block of the given type. */
    public static boolean getToBlock(String name) {
        IBaritone baritone = get();
        if (baritone == null || name == null || name.isBlank()) return false;

        baritone.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(name));
        return true;
    }

    /** Follows a player by name until the process is cancelled. */
    public static boolean follow(String playerName) {
        IBaritone baritone = get();
        if (baritone == null || playerName == null || playerName.isBlank()) return false;

        String needle = playerName.toLowerCase(Locale.ROOT);
        Predicate<Entity> filter = entity ->
            entity instanceof PlayerEntity player
                && player.getGameProfile().name().toLowerCase(Locale.ROOT).equals(needle);

        baritone.getFollowProcess().follow(filter);
        return true;
    }

    /** Follows any entity matching a predicate (used for item pickup too). */
    public static boolean follow(Predicate<Entity> filter) {
        IBaritone baritone = get();
        if (baritone == null || filter == null) return false;

        baritone.getFollowProcess().follow(filter);
        return true;
    }

    /** Picks up dropped items matching a predicate. */
    public static boolean pickupItems(Predicate<ItemStack> filter) {
        IBaritone baritone = get();
        if (baritone == null || filter == null) return false;

        baritone.getFollowProcess().pickup(filter);
        return true;
    }

    /** True while the follow process is tracking something. */
    public static boolean isFollowing() {
        IBaritone baritone = get();
        if (baritone == null) return false;

        List<Entity> following = baritone.getFollowProcess().following();
        return following != null && !following.isEmpty();
    }

    /** Runs Baritone's own chunk explorer centred on a chunk coordinate. */
    public static boolean explore(int centerX, int centerZ) {
        IBaritone baritone = get();
        if (baritone == null) return false;

        baritone.getExploreProcess().explore(centerX, centerZ);
        return true;
    }

    /** Harvests nearby crops, optionally bounded to a radius around the player. */
    public static boolean farm(int range) {
        IBaritone baritone = get();
        if (baritone == null) return false;

        if (range <= 0) baritone.getFarmProcess().farm();
        else baritone.getFarmProcess().farm(range);
        return true;
    }

    /** Builds a schematic from the {@code schematics} folder at the given origin. */
    @SuppressWarnings("deprecation")
    public static boolean build(String schematicFile, int x, int y, int z) {
        IBaritone baritone = get();
        if (baritone == null || schematicFile == null || schematicFile.isBlank()) return false;

        return baritone.getBuilderProcess().build(schematicFile, new BlockPos(x, y, z));
    }

    /** True while the builder process is paused. */
    public static boolean builderPaused() {
        IBaritone baritone = get();
        return baritone != null && baritone.getBuilderProcess().isPaused();
    }

    public static void builderPause() {
        IBaritone baritone = get();
        if (baritone != null) baritone.getBuilderProcess().pause();
    }

    public static void builderResume() {
        IBaritone baritone = get();
        if (baritone != null) baritone.getBuilderProcess().resume();
    }

    /** The current selection corners, or an empty list when nothing is selected. */
    public static List<ISelection> selections() {
        IBaritone baritone = get();
        if (baritone == null) return List.of();

        ISelection[] selections = baritone.getSelectionManager().getSelections();
        return selections == null ? List.of() : List.of(selections);
    }

    /** Cancels every Baritone process, not just pathing. */
    public static void cancelAllProcesses() {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getMineProcess().onLostControl();
        baritone.getFollowProcess().onLostControl();
        baritone.getExploreProcess().onLostControl();
        baritone.getFarmProcess().onLostControl();
        baritone.getBuilderProcess().onLostControl();
        baritone.getGetToBlockProcess().onLostControl();
        stop();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
