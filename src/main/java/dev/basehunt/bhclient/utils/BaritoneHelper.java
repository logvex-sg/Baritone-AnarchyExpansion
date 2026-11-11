package dev.basehunt.bhclient.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.BlockPos;

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
     * <p>The provider is resolved once and cached. Baritone builds its singleton during client
     * initialisation, before this addon loads, so the cached value stays valid for the client's lifetime.
     */
    public static IBaritone get() {
        if (!AVAILABLE) return null;

        IBaritone local = cached;
        if (local != null) return local;

        if (!probed) {
            synchronized (BaritoneHelper.class) {
                if (!probed) {
                    try {
                        local = BaritoneAPI.getProvider().getPrimaryBaritone();
                        cached = local;
                        if (local == null) probeError = "Baritone has no primary instance";
                    } catch (Throwable t) {
                        cached = null;
                        probeError = describe(t);
                    } finally {
                        probed = true;
                    }
                } else {
                    local = cached;
                }
            }
        }

        return local;
    }

    /** True when Baritone is installed and its API actually answers. */
    public static boolean usable() {
        return get() != null;
    }

    /** A short reason Baritone is unusable, or an empty string when it is fine. */
    public static String health() {
        if (!AVAILABLE) return PRESENCE_ERROR;
        if (get() == null) return probeError.isEmpty() ? "Baritone is not responding" : probeError;
        return "";
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
        if (get() == null) return null;
        return BaritoneAPI.getSettings();
    }

    public static boolean isPathing() {
        IBaritone baritone = get();
        return baritone != null && baritone.getPathingBehavior().isPathing();
    }

    public static boolean isElytraLoaded() {
        IBaritone baritone = get();
        return baritone != null && baritone.getElytraProcess().isLoaded();
    }

    /** True when the elytra process is running but can be interrupted without losing the route. */
    public static boolean isElytraSafeToCancel() {
        IBaritone baritone = get();
        return baritone != null && baritone.getElytraProcess().isLoaded() && baritone.getElytraProcess().isSafeToCancel();
    }

    /** True when Baritone is actively flying the elytra or walking a path. */
    public static boolean isMoving() {
        return isPathing() || isElytraLoaded();
    }

    public static void stop() {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getPathingBehavior().cancelEverything();
        baritone.getElytraProcess().onLostControl();
    }

    /** Flies to a destination using Baritone's nether-pathfinder powered elytra process. */
    public static void elytraPathTo(BlockPos destination) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getElytraProcess().pathTo(destination);
    }

    public static void elytraPathTo(Goal goal) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getElytraProcess().pathTo(goal);
    }

    public static void walkTo(BlockPos destination) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(destination, 2));
    }

    public static BlockPos currentDestination() {
        IBaritone baritone = get();
        if (baritone == null) return null;

        return baritone.getElytraProcess().currentDestination();
    }

    /** Forgets the elytra process's cached state, forcing a clean re-plan on the next path request. */
    public static void resetElytraState() {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getElytraProcess().resetState();
    }

    public static void runCommand(String command) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getCommandManager().execute(command);
    }

    public static String getPrefix() {
        Settings settings = settings();
        return settings == null ? "" : settings.prefix.value;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
