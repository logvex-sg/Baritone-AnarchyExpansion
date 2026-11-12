package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Lease;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.ElytraDeployment;
import dev.basehunt.bhclient.core.logic.ExplorationPlanner;
import dev.basehunt.bhclient.core.logic.FlightState;
import dev.basehunt.bhclient.core.logic.TargetPlanner;
import dev.basehunt.bhclient.systems.ExplorationStore;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.ElytraController;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.FlightController;
import dev.basehunt.bhclient.utils.FlightPlanner;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Second generation base hunter: no fireworks, faster and reactive.
 *
 * <p>Where {@link BaseHunter} flies a fixed pattern using Baritone's elytra pathfinder, this module flies
 * itself. That difference is what removes the firework requirement: Baritone's elytra pathfinder plans
 * around rocket propulsion and lands when rockets run out, while {@link ElytraController} drives Meteor's
 * ElytraFly in Bounce mode, which recasts the elytra every tick and sustains flight indefinitely without
 * consuming rockets. A flight is therefore limited by elytra durability alone, and
 * {@link ElytraManager} swaps in spares so any durability of elytra will do.
 *
 * <p>It is faster and smarter because it does not commit to a blind sweep:
 *
 * <ul>
 *   <li>targets come from {@link NewChunks} detections, clustered and ranked, so it flies at fresh terrain
 *       rather than empty ocean;</li>
 *   <li>{@link FlightPlanner} orders those targets into a nearest-neighbour tour, so it stops crossing its
 *       own path;</li>
 *   <li>it adapts its cruise altitude to the terrain underneath instead of holding one fixed height;</li>
 *   <li>when nothing has been found for a while it pushes a frontier waypoint outward to keep exploring.</li>
 * </ul>
 *
 * <p>Baritone is not required for any of this. It is used opportunistically, when installed, purely to
 * pre-generate chunks along the next leg so the client is not flying into unloaded terrain.
 */
public class BaseHunterV2 extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgExploration = settings.createGroup("Exploration");
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print autopilot progress and events in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoStart = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-start")
        .description("Begin flying as soon as the module is enabled and you are in a world.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> stopV1 = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-base-hunter-v1")
        .description("Disable Base Hunter when this module starts, so two autopilots never fight.")
        .defaultValue(true)
        .build()
    );

    // Flight

    private final Setting<Double> cruiseY = sgFlight.add(new DoubleSetting.Builder()
        .name("cruise-y")
        .description("Preferred flight altitude. Terrain avoidance overrides this when the ground is higher.")
        .defaultValue(180)
        .min(-64)
        .max(2000)
        .sliderRange(-64, 400)
        .build()
    );

    private final Setting<Boolean> terrainFollow = sgFlight.add(new BoolSetting.Builder()
        .name("terrain-follow")
        .description("Raise altitude automatically when terrain below is higher than the cruise altitude.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> clearance = sgFlight.add(new IntSetting.Builder()
        .name("clearance")
        .description("Blocks of clearance kept above terrain when terrain-following.")
        .defaultValue(40)
        .min(8)
        .sliderRange(8, 160)
        .visible(terrainFollow::get)
        .build()
    );

    private final Setting<Integer> elytraReplaceDurability = sgFlight.add(new IntSetting.Builder()
        .name("elytra-replace-durability")
        .description("Swap in a spare elytra when the worn one drops to this much durability. 0 disables.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Integer> elytraMinimumDurability = sgFlight.add(new IntSetting.Builder()
        .name("elytra-minimum-durability")
        .description("An elytra below this durability is not considered flight-worthy for a new leg.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Integer> maxDeployAttempts = sgFlight.add(new IntSetting.Builder()
        .name("max-deploy-attempts")
        .description("Give up redeploying the elytra after this many failed launches, rather than retrying forever.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> deployDelayMs = sgFlight.add(new IntSetting.Builder()
        .name("deploy-delay-ms")
        .description("Base delay between elytra deployment attempts, in milliseconds. Backs off exponentially.")
        .defaultValue(1000)
        .min(100)
        .sliderRange(100, 10000)
        .build()
    );

    private final Setting<Boolean> autoRedeploy = sgFlight.add(new BoolSetting.Builder()
        .name("auto-redeploy")
        .description("Automatically re-equip and relaunch after a landing or interrupted flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resumeAfterInterruption = sgFlight.add(new BoolSetting.Builder()
        .name("resume-after-interruption")
        .description("Remember the current target across landings and dimension changes, and fly to it again.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> unsafeAltitudeMargin = sgFlight.add(new IntSetting.Builder()
        .name("unsafe-altitude-margin")
        .description("Do not attempt a launch when the player is this close to the world ceiling.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> useBaritonePregen = sgFlight.add(new BoolSetting.Builder()
        .name("baritone-pregen")
        .description("Use Baritone to pre-generate chunks along the next leg, if Baritone is installed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> pregenLegLength = sgFlight.add(new IntSetting.Builder()
        .name("pregen-leg-length")
        .description("How far ahead to pre-generate.")
        .defaultValue(512)
        .min(128)
        .sliderRange(128, 4096)
        .visible(() -> useBaritonePregen.get())
        .build()
    );

    // Exploration

    private final Setting<Boolean> useTargetPlanner = sgExploration.add(new BoolSetting.Builder()
        .name("smart-targets")
        .description("Rank destinations by confidence, distance and whether they were seen before.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackExplored = sgExploration.add(new BoolSetting.Builder()
        .name("track-explored")
        .description("Remember which chunks have been covered so empty territory is not re-flown.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> skipExploredTargets = sgExploration.add(new BoolSetting.Builder()
        .name("skip-explored-targets")
        .description("Drop queued targets whose chunk has already been explored.")
        .defaultValue(true)
        .visible(trackExplored::get)
        .build()
    );

    private final Setting<Boolean> expandWhenStalled = sgExploration.add(new BoolSetting.Builder()
        .name("expand-when-stalled")
        .description("Push the sweep further out when a region stops producing discoveries.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useCustomRoute = sgExploration.add(new BoolSetting.Builder()
        .name("use-custom-route")
        .description("Fly the route waypoints set with /bh route add before hunting detected chunks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> loopCustomRoute = sgExploration.add(new BoolSetting.Builder()
        .name("loop-custom-route")
        .description("When the route waypoints run out, start the route again instead of hunting chunks.")
        .defaultValue(false)
        .visible(useCustomRoute::get)
        .build()
    );

    private final Setting<Integer> stallMinutes = sgExploration.add(new IntSetting.Builder()
        .name("stall-minutes")
        .description("Minutes without a discovery before exploration expands.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 60)
        .visible(expandWhenStalled::get)
        .build()
    );

    private final Setting<Integer> milestoneInterval = sgExploration.add(new IntSetting.Builder()
        .name("milestone-interval")
        .description("Report a milestone every this many blocks flown. 0 disables milestones.")
        .defaultValue(50000)
        .min(0)
        .sliderRange(0, 500000)
        .build()
    );

    private final Setting<ExploreMode> exploreMode = sgExploration.add(new EnumSetting.Builder<ExploreMode>()
        .name("explore-mode")
        .description("Pattern to fly. Adaptive reacts to detections; the others fly a fixed sweep.")
        .defaultValue(ExploreMode.Adaptive)
        .build()
    );

    private final Setting<Integer> patternLegLength = sgExploration.add(new IntSetting.Builder()
        .name("pattern-leg-length")
        .description("Length of each grid/adaptive leg, in blocks.")
        .defaultValue(1024)
        .min(64)
        .sliderRange(64, 8192)
        .visible(() -> exploreMode.get() == ExploreMode.Grid || exploreMode.get() == ExploreMode.Adaptive)
        .build()
    );

    private final Setting<Integer> patternSpacing = sgExploration.add(new IntSetting.Builder()
        .name("pattern-spacing")
        .description("Distance between parallel legs, in blocks. Smaller means denser coverage.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 2048)
        .visible(() -> exploreMode.get() != ExploreMode.Custom)
        .build()
    );

    private final Setting<Integer> patternLegs = sgExploration.add(new IntSetting.Builder()
        .name("pattern-legs")
        .description("How many legs the grid/adaptive sweep contains before it is regenerated.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 256)
        .visible(() -> exploreMode.get() == ExploreMode.Grid || exploreMode.get() == ExploreMode.Adaptive)
        .build()
    );

    private final Setting<Integer> spiralRadius = sgExploration.add(new IntSetting.Builder()
        .name("spiral-radius")
        .description("How far out the spiral grows before it is regenerated.")
        .defaultValue(4096)
        .min(256)
        .sliderRange(256, 32768)
        .visible(() -> exploreMode.get() == ExploreMode.Spiral)
        .build()
    );

    private final Setting<Double> headingDegrees = sgExploration.add(new DoubleSetting.Builder()
        .name("heading-degrees")
        .description("Compass bearing to fly in Heading mode. 0 is north, 90 is east.")
        .defaultValue(0.0)
        .min(0.0)
        .max(360.0)
        .sliderRange(0.0, 360.0)
        .visible(() -> exploreMode.get() == ExploreMode.Heading)
        .build()
    );

    /** How the sweep pattern is generated. */
    public enum ExploreMode {
        Adaptive,
        Grid,
        Spiral,
        Heading,
        Custom
    }

    private final Setting<Aggression> targetAggression = sgExploration.add(new EnumSetting.Builder<Aggression>()
        .name("target-aggression")
        .description("How strongly to favour high-confidence targets over nearby ones.")
        .defaultValue(Aggression.Balanced)
        .visible(useTargetPlanner::get)
        .build()
    );

    /** How eagerly the target planner chases confident discoveries. */
    public enum Aggression {
        Cautious,
        Balanced,
        Aggressive
    }

    // Targets

    private final Setting<Integer> clusterRadius = sgTargets.add(new IntSetting.Builder()
        .name("cluster-radius")
        .description("Chunks within this many chunks of each other count as one target.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargets.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many clustered targets to keep in the queue at once.")
        .defaultValue(32)
        .min(1)
        .sliderRange(1, 256)
        .build()
    );

    private final Setting<Boolean> nearestFirst = sgTargets.add(new BoolSetting.Builder()
        .name("nearest-first")
        .description("Reorder targets into a nearest-neighbour tour so the route does not criss-cross.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> priorityRadius = sgTargets.add(new IntSetting.Builder()
        .name("interest-radius")
        .description("Clusters within this many blocks of a known stash or logout spot are prioritised.")
        .defaultValue(256)
        .min(0)
        .sliderRange(0, 2048)
        .build()
    );

    private final Setting<Integer> frontierStep = sgTargets.add(new IntSetting.Builder()
        .name("frontier-step")
        .description("How far past the furthest known chunk to push when nothing new is being found.")
        .defaultValue(2000)
        .min(256)
        .sliderRange(256, 20000)
        .build()
    );

    private final Setting<Integer> frontierAfterSeconds = sgTargets.add(new IntSetting.Builder()
        .name("frontier-after-seconds")
        .description("Seconds without a new detection before expanding the frontier. 0 disables.")
        .defaultValue(90)
        .min(0)
        .sliderRange(0, 900)
        .build()
    );

    // Safety

    private final Setting<Boolean> pauseNearPlayers = sgSafety.add(new BoolSetting.Builder()
        .name("pause-near-players")
        .description("Pause the route when another player comes close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> playerRadius = sgSafety.add(new IntSetting.Builder()
        .name("player-radius")
        .description("Distance at which another player triggers a pause.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 1024)
        .visible(pauseNearPlayers::get)
        .build()
    );

    private final Setting<Boolean> resumeAfterPlayersLeave = sgSafety.add(new BoolSetting.Builder()
        .name("resume-after-players-leave")
        .description("Resume the route once no players are within range.")
        .defaultValue(true)
        .visible(pauseNearPlayers::get)
        .build()
    );

    private final Setting<Boolean> landOnArrival = sgSafety.add(new BoolSetting.Builder()
        .name("land-on-arrival")
        .description("Stop and land when the target queue is exhausted.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logOutWhenDone = sgSafety.add(new BoolSetting.Builder()
        .name("log-out-when-done")
        .description("Disconnect instead of just stopping when the hunt finishes.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> abortWithoutElytra = sgSafety.add(new BoolSetting.Builder()
        .name("abort-without-elytra")
        .description("Stop when no elytra remain at all. Any durability of elytra is accepted.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the current target and queued targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the target boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Colour of the active target.")
        .defaultValue(new SettingColor(0, 200, 255))
        .build()
    );

    private final Setting<SettingColor> queueColor = sgRender.add(new ColorSetting.Builder()
        .name("queue-color")
        .description("Colour of queued targets.")
        .defaultValue(new SettingColor(120, 120, 255, 140))
        .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Hide target boxes further away than this.")
        .defaultValue(4096)
        .min(128)
        .sliderRange(128, 20000)
        .visible(render::get)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Boolean> notifyStateChanges = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-state-changes")
        .description("Send start, stop and completion messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> progressIntervalMinutes = sgWebhook.add(new IntSetting.Builder()
        .name("progress-interval-minutes")
        .description("Minutes between progress reports. 0 disables.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 60)
        .build()
    );

    // State

    /** The scheduler lease covering movement and rotation, held while a route is flying. */
    private Lease controlLease;
    /** True once a custom route has been flown to its last waypoint. */
    private boolean customRouteDrained;
    /** Index of the next leg to emit when flying a generated sweep pattern. */
    private int patternLeg;
    /** Dimension seen on the previous tick, used to detect a portal transition. */
    private String lastDimension;
    /** Chunks marked explored since the store was last flushed to disk. */
    private int exploredSinceSave;

    private final FlightPlanner planner = new FlightPlanner();
    private final Deque<FlightPlanner.Target> queue = new ArrayDeque<>();

    /** Owns elytra deployment, durability and redeployment across landings and interruptions. */
    private FlightController flight;

    private FlightPlanner.Target current;
    private boolean started;
    private boolean waitingForWorld;
    private boolean paused;
    private double travelledBlocks;
    private double lastX, lastZ;
    private long lastProgressWebhook;
    private long startedAt;
    private int targetsVisited;
    /** Distance at which the next "flown this far" milestone fires. */
    private double nextMilestoneBlocks;
    private int elytraSwaps;
    private boolean warnedNotFlying;

    /** Last observed flight state, used to detect a landing or an interruption. */
    private FlightState lastFlightState = FlightState.UNKNOWN;
    /** True while the route is waiting for the flight controller to redeploy the elytra. */
    private boolean awaitingRedeploy;
    /** The target being flown to when flight was interrupted, remembered so it can be resumed. */
    private FlightPlanner.Target interruptedTarget;

    public BaseHunterV2() {
        super(BaseHuntingAddon.CATEGORY, "base-hunter-v2",
            "Firework-free elytra base hunter. Flies itself and reacts to new chunk detections.");

        flight = new FlightController(name, 6, 1000, 8000);
    }

    @Override
    public void onActivate() {
        if (stopV1.get()) {
            BaseHunter v1 = Modules.get().get(BaseHunter.class);
            if (v1 != null && v1.isActive()) v1.disable();
        }

        if (!ElytraController.available()) {
            error("Meteor's Elytra Fly module is unavailable, cannot fly.");
            toggle();
            return;
        }

        planner.clear();
        queue.clear();
        current = null;
        started = false;
        paused = false;
        travelledBlocks = 0;
        targetsVisited = 0;
        elytraSwaps = 0;
        awaitingRedeploy = false;
        interruptedTarget = null;
        lastFlightState = FlightState.UNKNOWN;
        lastProgressWebhook = System.currentTimeMillis();

        flight = new FlightController(name, maxDeployAttempts.get(), deployDelayMs.get(), 8000);
        flight.setMinimumDurability(elytraMinimumDurability.get());
        flight.setReplaceThreshold(elytraReplaceDurability.get());

        if (autoStart.get()) {
            if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) {
                waitingForWorld = true;
                if (chatFeedback.get()) info("Waiting for a world to load before starting.");
            } else {
                start();
            }
        }
    }

    @Override
    public void onDeactivate() {
        ElytraController.disable();
        releaseControl();
        if (flight != null) flight.setWantsFlight(false);
        current = null;
        started = false;
        awaitingRedeploy = false;

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter V2 stopped")
                .color(0xFF5555)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Flown", "%,d blocks".formatted((long) travelledBlocks))
                .field("Targets visited", String.valueOf(targetsVisited))
                .footer("Baritone Anarchy Expansion"));
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!waitingForWorld) return;

        waitingForWorld = false;
        start();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        // The world and player are gone, so no flight can continue. Stop cleanly and drop the control lease
        // rather than leaving a stale owner holding movement through the disconnect.
        releaseControl();
        if (flight != null) flight.onDisconnected();
        if (current != null) interruptedTarget = current;
        current = null;
        started = false;
        awaitingRedeploy = false;
        lastDimension = null;
    }

    /**
     * Tears the flight down when the player changes dimension.
     *
     * <p>ElytraFly, the altitude steering and the recorded target are all dimension-specific: carrying any of
     * them across a portal would send the player toward coordinates that mean nothing in the new world. The
     * route is paused with its target saved so it can resume once the player is back in the right dimension.
     */
    private void checkDimensionChange() {
        String dimension = ServerUtils.dimension();
        if (dimension.equals(lastDimension)) return;

        String previous = lastDimension;
        lastDimension = dimension;

        if (previous == null) return;

        if (flight != null) flight.onDimensionChanged();
        ElytraController.disable();

        if (current != null) {
            interruptedTarget = current;
            current = null;
        }

        releaseControl();

        if (chatFeedback.get()) {
            warning("Dimension changed (%s -> %s), saving route progress.", previous, dimension);
        }
    }

    /** Begins the hunt. Called by auto-start and the {@code /bh v2 start} command. */
    public void start() {
        if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) {
            waitingForWorld = true;
            return;
        }

        if (!ElytraManager.hasElytraEquipped() && !ElytraManager.equipBest()) {
            error("No elytra found. Any durability of elytra works, but you need at least one.");
            if (abortWithoutElytra.get()) toggle();
            return;
        }

        // Refuse to start a journey the carried elytras cannot finish, rather than stranding the player mid-map.
        String invalid = ElytraManager.validateJourney(elytraMinimumDurability.get(), 0);
        if (invalid != null) {
            error("Equipment check failed: %s.", invalid);
            publish(AutomationEvent.Type.ELYTRA_MISSING, java.util.Map.of("reason", invalid));
            if (abortWithoutElytra.get()) toggle();
            return;
        }

        started = true;
        startedAt = System.currentTimeMillis();
        travelledBlocks = 0;
        lastX = ServerUtils.mc().player.getX();
        lastZ = ServerUtils.mc().player.getZ();
        awaitingRedeploy = false;
        interruptedTarget = null;
        customRouteDrained = false;
        lastDimension = ServerUtils.dimension();
        nextMilestoneBlocks = milestoneInterval.get();
        lastFlightState = ElytraManager.flightState();

        flight = new FlightController(name, maxDeployAttempts.get(), deployDelayMs.get(), 8000);
        flight.setMinimumDurability(elytraMinimumDurability.get());
        flight.setReplaceThreshold(elytraReplaceDurability.get());
        flight.setWantsFlight(true);
        flight.beginLeg();

        if (!ElytraController.available()) {
            error("Could not start Meteor's Elytra Fly.");
            toggle();
            return;
        }

        // The flight controller owns deployment from here; it will launch on the next tick. Enable now so the
        // first frame does not stall waiting for a tick.
        ElytraController.enable(effectiveCruiseY(), true);

        if (chatFeedback.get()) {
            info("Base Hunter V2 started. Elytra: (highlight)%s(default).", ElytraManager.status());
            info("Fireworks are not required in this mode.");
        }

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter V2 started")
                .description("Firework-free elytra sweep.")
                .color(0x00C8FF)
                .field("Elytra", ElytraManager.status())
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Dimension", ServerUtils.dimension())
                .field("Baritone", dev.basehunt.bhclient.utils.BaritoneHelper.AVAILABLE ? "available" : "not installed")
                .footer("Baritone Anarchy Expansion"));
        }

        publish(AutomationEvent.Type.FLIGHT_STARTED, java.util.Map.of("elytra", ElytraManager.status()));
        publish(AutomationEvent.Type.ROUTE_STARTED, java.util.Map.of(
            "position", ServerUtils.coordinates(), "dimension", ServerUtils.dimension()));
        refreshQueue();
        if (queue.isEmpty()) pushFrontier();
        advance();
    }

    /** Stops flying but leaves the module enabled. */
    public void stop() {
        started = false;
        current = null;
        queue.clear();
        awaitingRedeploy = false;
        if (flight != null) flight.setWantsFlight(false);
        ElytraController.disable();
        releaseControl();

        if (chatFeedback.get()) info("Base Hunter V2 stopped.");
    }

    /** Pauses the route without losing the target, used by the command and the survival manager. */
    public void pause() {
        if (!started || paused) return;
        paused = true;
        ElytraController.disable();
        if (flight != null) flight.setWantsFlight(false);
        if (chatFeedback.get()) info("Route paused.");
        publish(AutomationEvent.Type.ROUTE_INTERRUPTED, java.util.Map.of("reason", "paused"));
    }

    /** Resumes a paused route. */
    public void resume() {
        if (!started || !paused) return;
        paused = false;
        if (flight != null) {
            flight.setWantsFlight(true);
            flight.beginLeg();
        }
        if (chatFeedback.get()) info("Route resumed.");
        publish(AutomationEvent.Type.ROUTE_RESUMED, java.util.Map.of());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) return;

        checkDimensionChange();
        if (!started) return;

        handleSafety();

        if (paused) {
            if (flight != null) flight.setWantsFlight(false);
            releaseControl();
            return;
        }

        if (flight != null) flight.setWantsFlight(true);

        // Claim movement and rotation for this tick. If a higher-priority automation (equipment recovery, a
        // forced landing) has taken them, yield rather than fighting over the same player controls.
        if (!acquireControl()) return;

        // The flight controller owns deployment, replacement and redeployment. Its verdict decides whether the
        // route keeps running this tick.
        ElytraDeployment.Action action = flight.tick(effectiveCruiseY());
        handleFlightAction(action);

        if (awaitingRedeploy) return;

        trackTravel();
        sendProgressIfDue();

        if (current == null) {
            advance();
            return;
        }

        if (ElytraController.arrivedHorizontally(current.x(), current.z())) {
            onTargetReached();
            return;
        }

        ElytraController.steerToGround(current.x(), current.z(), effectiveCruiseY());
    }

    /**
     * Takes the movement and rotation lease for this tick.
     *
     * <p>Failing to get it is not an error: another automation outranks the route right now, so the route
     * simply does not touch the player this tick. The lease is refreshed every tick while held and carries a
     * short timeout, so a module that stops ticking releases control on its own.
     */
    private boolean acquireControl() {
        AutomationManager manager = AutomationManager.get();
        if (manager == null) return true;

        controlLease = manager.tryAcquire(name, Priority.ACTIVE_EXPLORATION,
            java.util.Set.of(ControlResource.MOVEMENT, ControlResource.ROTATION));
        return controlLease != null;
    }

    private void releaseControl() {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.releaseAll(name);
        controlLease = null;
    }

    /** Reacts to the flight controller's decision: abort, replace or redeploy after a landing. */
    private void handleFlightAction(ElytraDeployment.Action action) {
        FlightState state = ElytraManager.flightState();
        boolean wasGliding = lastFlightState == FlightState.GLIDING;
        boolean nowNotGliding = state != FlightState.GLIDING;

        if (action == ElytraDeployment.Action.ABORT) {
            stopAndMaybeDisconnect("No elytra left");
            return;
        }

        if (action == ElytraDeployment.Action.REPLACE) {
            elytraSwaps++;
            if (chatFeedback.get()) info("Elytra swapped. %s", ElytraManager.status());
        }

        if (wasGliding && nowNotGliding) {
            // The glide ended - a landing, a mid-air break, or a dimension change. Record where we were so the
            // current target is not lost, then let the controller try to get back in the air.
            interruptedTarget = current;
            publish(AutomationEvent.Type.FLIGHT_INTERRUPTED, java.util.Map.of(
                "state", state.name().toLowerCase(),
                "target", current == null ? "none" : current.x() + "," + current.z()));
        }

        if (!autoRedeploy.get() || state == FlightState.GLIDING) {
            awaitingRedeploy = false;
            // A live glide means the previous interruption is over, so re-arm the one-time warning.
            if (state == FlightState.GLIDING) warnedNotFlying = false;
            lastFlightState = state;
            return;
        }

        awaitingRedeploy = true;

        if (flight.launchExhausted()) {
            // Retries exhausted. Resume the interrupted target if asked, otherwise treat the flight as over.
            if (resumeAfterInterruption.get() && interruptedTarget != null) {
                current = interruptedTarget;
                interruptedTarget = null;
                flight.beginLeg();
                if (chatFeedback.get()) {
                    info("Redeploy attempts exhausted, retrying the interrupted target.");
                }
            } else {
                stopAndMaybeDisconnect("Could not redeploy the elytra");
            }
            return;
        }

        // Still within the retry budget: the controller is launching in the background, so hold the route.
        if (chatFeedback.get() && warnedNotFlying == false && flight.launchAttempts() == 1) {
            warnedNotFlying = true;
            warning("Flight interrupted, attempting to redeploy (%s).", state.name().toLowerCase());
        }

        lastFlightState = state;
    }

    private void handleSafety() {
        if (!pauseNearPlayers.get()) return;
        if (ServerUtils.mc().player.isCreative()) return;

        PlayerEntity nearest = nearestPlayer();

        if (nearest != null) {
            if (!paused) {
                paused = true;
                ElytraController.disable();

                String name = nearest.getName().getString();
                int distance = (int) ServerUtils.mc().player.distanceTo(nearest);

                if (chatFeedback.get()) warning("Pausing, %s is %d blocks away.", name, distance);
                planner.noteProgress();
            }

            return;
        }

        if (paused && resumeAfterPlayersLeave.get()) {
            paused = false;
            if (chatFeedback.get()) info("Players gone, resuming.");
            if (flight != null) {
                flight.setWantsFlight(true);
                flight.beginLeg();
            }
            ElytraController.enable(effectiveCruiseY(), true);
        }
    }

    private PlayerEntity nearestPlayer() {
        double radiusSq = (double) playerRadius.get() * playerRadius.get();
        PlayerEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PlayerEntity player : ServerUtils.mc().world.getPlayers()) {
            if (player == ServerUtils.mc().player) continue;

            double distance = ServerUtils.mc().player.squaredDistanceTo(player);
            if (distance > radiusSq) continue;

            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    private void trackTravel() {
        double x = ServerUtils.mc().player.getX();
        double z = ServerUtils.mc().player.getZ();

        travelledBlocks += Math.sqrt(Math.pow(x - lastX, 2) + Math.pow(z - lastZ, 2));
        lastX = x;
        lastZ = z;

        // Record the chunk under the player as it is crossed, not only on arrival at a target: a long leg
        // passes over many chunks, and every one of them has now been seen. Saving is deferred to a counter
        // so a fast flight does not write the store to disk on every tick.
        if (trackExplored.get()) {
            ExplorationStore store = ExplorationStore.get();
            if (store != null) {
                int chunkX = (int) Math.floor(x) >> 4;
                int chunkZ = (int) Math.floor(z) >> 4;
                if (!store.regions().wasExplored(chunkX, chunkZ)) {
                    store.regions().markExplored(chunkX, chunkZ);
                    if (++exploredSinceSave >= 32) {
                        exploredSinceSave = 0;
                        store.save();
                    }
                }
            }
        }
    }

    /**
     * Replaces the queue with the user's custom route waypoints when that mode is on.
     *
     * @return true when the queue was filled from the route, so the caller should not rebuild it from chunks
     */
    private boolean queueFromCustomRoute() {
        boolean custom = useCustomRoute.get() || exploreMode.get() == ExploreMode.Custom;
        if (!custom) return false;

        ExplorationStore store = ExplorationStore.get();
        if (store == null) return false;

        var waypoints = store.customWaypoints();
        if (waypoints.isEmpty()) return false;

        if (customRouteDrained) {
            if (!loopCustomRoute.get()) return false;
            customRouteDrained = false;
        }

        List<FlightPlanner.Target> targets = new ArrayList<>(waypoints.size());
        for (ExplorationStore.Waypoint waypoint : waypoints) {
            targets.add(new FlightPlanner.Target(
                waypoint.pos.getX(), waypoint.pos.getZ(), 1, 0, "route " + waypoint.label));
        }

        queue.clear();
        queue.addAll(targets);
        return true;
    }

    /**
     * Fills the queue from a generated sweep pattern (grid, spiral or compass heading).
     *
     * <p>The pattern is regenerated one leg at a time from {@link #patternLeg} rather than materialised up
     * front, so a very large sweep does not build an enormous waypoint list in memory. Waypoints whose chunk
     * has already been explored are skipped when explored-tracking is on, which is what stops a grid from
     * re-covering ground a previous session already crossed.
     *
     * @return true when the queue was filled from the pattern
     */
    private boolean queueFromPattern() {
        ExploreMode mode = exploreMode.get();
        if (mode == ExploreMode.Custom) return false;

        int originX = ServerUtils.mc().player.getBlockPos().getX();
        int originZ = ServerUtils.mc().player.getBlockPos().getZ();

        List<ExplorationPlanner.Waypoint> points = switch (mode) {
            case Grid -> ExplorationPlanner.grid(originX, originZ,
                patternLegLength.get(), patternSpacing.get(), patternLegs.get());
            case Adaptive -> ExplorationPlanner.adaptive(originX, originZ,
                patternLegLength.get(), patternLegs.get(), interestPoints(),
                patternSpacing.get() * 2, patternSpacing.get(), patternSpacing.get() * 4);
            case Spiral -> ExplorationPlanner.spiral(originX, originZ,
                patternSpacing.get(), spiralRadius.get());
            case Heading -> List.of(ExplorationPlanner.headingLeg(originX, originZ,
                headingDegrees.get(), patternLegLength.get(), patternLeg, patternSpacing.get()));
            default -> List.of();
        };

        if (points.isEmpty()) return false;

        patternLeg++;

        ExplorationStore store = trackExplored.get() ? ExplorationStore.get() : null;

        List<FlightPlanner.Target> targets = new ArrayList<>(points.size());
        for (ExplorationPlanner.Waypoint point : points) {
            if (store != null && skipExploredTargets.get()
                && store.regions().wasExplored(point.x() >> 4, point.z() >> 4)) {
                continue;
            }
            targets.add(new FlightPlanner.Target(point.x(), point.z(), 1, 0, point.label()));
        }

        if (targets.isEmpty()) return false;

        queue.clear();
        queue.addAll(targets);
        return true;
    }

    /** Interest points for adaptive spacing: known stashes and player logout spots near the sweep. */
    private List<int[]> interestPoints() {
        List<int[]> points = new ArrayList<>();

        StashManager stashManager = StashManager.get();
        if (stashManager != null) {
            for (StashManager.Stash stash : stashManager.forCurrentServer()) {
                points.add(new int[] {stash.pos.getX(), stash.pos.getZ()});
                if (points.size() >= 128) return points;
            }
        }

        dev.basehunt.bhclient.systems.PlayerTracker tracker = dev.basehunt.bhclient.systems.PlayerTracker.get();
        if (tracker != null) {
            for (dev.basehunt.bhclient.systems.PlayerTracker.Entry entry : tracker.forCurrentServer()) {
                if (!entry.hasLogoutSpot()) continue;
                points.add(new int[] {entry.logoutX, entry.logoutZ});
                if (points.size() >= 256) break;
            }
        }

        return points;
    }

    /** Rebuilds the target queue from current chunk detections, falling back to the sweep pattern. */
    private void refreshQueue() {
        if (queueFromCustomRoute()) return;

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (newChunks == null) return;

        Set<Long> detections = newChunks.getNewChunks();
        if (detections.isEmpty()) {
            queueFromPattern();
            return;
        }

        int playerX = ServerUtils.mc().player.getBlockPos().getX();
        int playerZ = ServerUtils.mc().player.getBlockPos().getZ();

        List<FlightPlanner.Target> targets = planner.buildTargets(
            detections,
            clusterRadius.get(),
            playerX,
            playerZ,
            maxTargets.get(),
            priorityRadius.get()
        );

        if (targets.isEmpty()) return;

        if (useTargetPlanner.get()) targets = rankTargets(targets, playerX, playerZ);
        else if (nearestFirst.get()) targets = FlightPlanner.orderByNearest(targets, playerX, playerZ);

        // Drop targets in terrain that has already been covered, so the sweep keeps moving outward.
        if (trackExplored.get() && skipExploredTargets.get()) {
            ExplorationStore store = ExplorationStore.get();
            if (store != null) {
                targets = targets.stream()
                    .filter(t -> !store.regions().wasExplored(t.x() >> 4, t.z() >> 4))
                    .toList();
            }
        }

        if (targets.isEmpty()) {
            // Every detection was already covered. Fall through to the sweep pattern so exploration keeps
            // moving outward instead of stalling on an empty queue.
            queueFromPattern();
            return;
        }

        // Keep the target being flown to; only replace what is still queued.
        queue.clear();
        queue.addAll(targets);
    }

    /**
     * Ranks cluster targets with the target planner, using the confidence of the best stash recorded inside
     * each cluster so a cluster that already contains a known base is prioritised over an empty one.
     */
    private List<FlightPlanner.Target> rankTargets(List<FlightPlanner.Target> targets, int playerX, int playerZ) {
        StashManager stashManager = StashManager.get();
        List<TargetPlanner.Candidate> candidates = new ArrayList<>(targets.size());
        Map<TargetPlanner.Candidate, FlightPlanner.Target> back = new HashMap<>();

        for (FlightPlanner.Target target : targets) {
            int confidence = 0;
            String kind = "unknown";
            boolean seen = false;

            if (stashManager != null) {
                StashManager.Stash nearby = stashManager.findNearby(ServerUtils.serverAddress(),
                    new BlockPos(target.x(), 64, target.z()), 128);
                if (nearby != null) {
                    confidence = nearby.score();
                    kind = nearby.classification;
                    seen = true;
                }
            }

            TargetPlanner.Candidate candidate = new TargetPlanner.Candidate(
                target.reason(), target.x(), target.z(), ServerUtils.dimension(),
                confidence, seen, java.lang.System.currentTimeMillis(), kind);

            candidates.add(candidate);
            back.put(candidate, target);
        }

        TargetPlanner.Weights weights = targetAggression.get() == Aggression.Aggressive
            ? TargetPlanner.Weights.aggressive()
            : targetAggression.get() == Aggression.Cautious
                ? TargetPlanner.Weights.cautious()
                : TargetPlanner.Weights.defaults();

        List<FlightPlanner.Target> ranked = new ArrayList<>();
        for (TargetPlanner.Scored scored : TargetPlanner.rank(candidates, playerX, playerZ, ServerUtils.dimension(), weights)) {
            ranked.add(back.get(scored.candidate()));
        }

        return ranked;
    }

    private void advance() {
        if (queue.isEmpty() && useCustomRoute.get() && !customRouteDrained) {
            customRouteDrained = true;
            if (loopCustomRoute.get()) {
                customRouteDrained = false;
                refreshQueue();
            } else {
                stopAndMaybeDisconnect("Custom route complete");
                return;
            }
        }

        if (queue.isEmpty()) {
            if (shouldPushFrontier()) {
                pushFrontier();
            } else {
                refreshQueue();
            }
        }

        current = queue.pollFirst();

        if (current == null) {
            stopAndMaybeDisconnect("No targets left");
            return;
        }

        ElytraController.steerToGround(current.x(), current.z(), effectiveCruiseY());

        if (chatFeedback.get()) {
            info("Target (highlight)%d, %d(default) - %s (%d blocks).",
                current.x(), current.z(), current.reason(),
                (int) ElytraController.horizontalDistanceTo(current.x(), current.z()));
        }
    }

    private boolean shouldPushFrontier() {
        if (frontierAfterSeconds.get() <= 0) return false;
        long stallMs = planner.millisSinceProgress();

        // A user-set stall threshold can force expansion even when the planner still has targets queued.
        if (expandWhenStalled.get() && stallMs >= stallMinutes.get() * 60_000L) {
            publish(AutomationEvent.Type.EXPLORATION_STALLED, Map.of("minutes", String.valueOf(stallMinutes.get())));
            return true;
        }

        return stallMs >= frontierAfterSeconds.get() * 1000L;
    }

    private void pushFrontier() {
        NewChunks newChunks = Modules.get().get(NewChunks.class);
        Set<Long> known = newChunks == null ? Set.of() : newChunks.getNewChunks();

        FlightPlanner.Target frontier = FlightPlanner.frontier(
            known,
            ServerUtils.mc().player.getBlockPos().getX(),
            ServerUtils.mc().player.getBlockPos().getZ(),
            frontierStep.get()
        );

        if (chatFeedback.get()) info("Nothing new nearby, pushing out to (highlight)%d, %d(default).", frontier.x(), frontier.z());
        queue.addLast(frontier);
        publish(AutomationEvent.Type.FRONTIER_PUSHED, java.util.Map.of(
            "x", String.valueOf(frontier.x()), "z", String.valueOf(frontier.z())));
    }

    private void onTargetReached() {
        if (current == null) return;

        planner.markVisited(current.x(), current.z());
        targetsVisited++;

        if (travelledBlocks >= nextMilestoneBlocks) {
            nextMilestoneBlocks = travelledBlocks + milestoneInterval.get();
            publish(AutomationEvent.Type.MILESTONE, java.util.Map.of(
                "flown", "%,d blocks".formatted((long) travelledBlocks),
                "targets", String.valueOf(targetsVisited),
                "chunks", String.valueOf(exploredCount())));
        }

        if (trackExplored.get()) {
            ExplorationStore store = ExplorationStore.get();
            if (store != null) {
                store.regions().markExplored(current.x() >> 4, current.z() >> 4);
                store.save();
            }
        }

        if (chatFeedback.get()) info("Reached (highlight)%d, %d(default).", current.x(), current.z());
        publish(AutomationEvent.Type.TARGET_REACHED, java.util.Map.of(
            "x", String.valueOf(current.x()), "z", String.valueOf(current.z()),
            "visited", String.valueOf(targetsVisited)));
        current = null;

        // Rebuild the queue from what has been detected since the last leg, then keep going.
        refreshQueue();
        advance();
    }

    private void stopAndMaybeDisconnect(String reason) {
        if (chatFeedback.get()) info("%s, stopping the hunt.", reason);
        publish(AutomationEvent.Type.ROUTE_COMPLETED, java.util.Map.of(
            "reason", reason, "visited", String.valueOf(targetsVisited)));

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter V2 finished")
                .description(reason)
                .color(0x00C8FF)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Flown", "%,d blocks".formatted((long) travelledBlocks))
                .field("Targets visited", String.valueOf(targetsVisited))
                .field("Elytra", ElytraManager.status())
                .field("Duration", durationText())
                .footer("Baritone Anarchy Expansion"));
        }

        started = false;
        current = null;
        queue.clear();

        if (landOnArrival.get()) ElytraController.disable();

        if (logOutWhenDone.get() && ServerUtils.mc().player != null) {
            ServerUtils.mc().player.networkHandler.getConnection()
                .disconnect(Text.literal("Base Hunter V2: " + reason));
        } else if (isActive()) {
            toggle();
        }
    }

    private void sendProgressIfDue() {
        if (progressIntervalMinutes.get() <= 0) return;
        if (!webhook.isEnabled() || WebhookSettings.globallyMuted()) return;

        long now = System.currentTimeMillis();
        if (now - lastProgressWebhook < progressIntervalMinutes.get() * 60_000L) return;

        lastProgressWebhook = now;

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        StashManager stashManager = StashManager.get();

        webhook.send(new WebhookManager.Embed()
            .title("Base Hunter V2 progress")
            .color(0x00C8FF)
            .field("Position", ServerUtils.coordinates())
            .field("Server", ServerUtils.serverAddress())
            .field("Target", current == null ? "none" : "%d, %d".formatted(current.x(), current.z()))
            .field("Queued targets", String.valueOf(queue.size()))
            .field("Flown", "%,d blocks".formatted((long) travelledBlocks))
            .field("Speed", "%.1f b/s".formatted(ElytraController.speedBlocksPerSecond()))
            .field("New chunks", String.valueOf(newChunks == null ? 0 : newChunks.getTrackedCount()))
            .field("Stashes", String.valueOf(stashManager == null ? 0 : stashManager.forCurrentServer().size()))
            .field("Elytra", ElytraManager.status())
            .field("Duration", durationText())
            .footer("Baritone Anarchy Expansion"));
    }

    private void publish(AutomationEvent.Type type, java.util.Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }

    private String durationText() {
        long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        return "%dh %dm".formatted(seconds / 3600, (seconds % 3600) / 60);
    }

    /**
     * Cruise altitude adjusted for the terrain ahead. Flying a fixed altitude either clips hills or wastes
     * time climbing over terrain that is not there, so the altitude follows the ground when asked to.
     */
    private double effectiveCruiseY() {
        double base = cruiseY.get();
        if (!terrainFollow.get()) return base;

        if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) return base;

        // Sample a short distance ahead so the climb starts before the hill arrives.
        double headingX = current == null ? 0 : current.x() - ServerUtils.mc().player.getX();
        double headingZ = current == null ? 0 : current.z() - ServerUtils.mc().player.getZ();
        double length = Math.sqrt(headingX * headingX + headingZ * headingZ);

        double lookX = ServerUtils.mc().player.getX();
        double lookZ = ServerUtils.mc().player.getZ();
        if (length > 1.0) {
            lookX += headingX / length * 96.0;
            lookZ += headingZ / length * 96.0;
        }

        int probeX = (int) Math.floor(lookX);
        int probeZ = (int) Math.floor(lookZ);

        int highest = ServerUtils.mc().world.getTopY(Heightmap.Type.MOTION_BLOCKING, probeX, probeZ);
        if (highest + clearance.get() > base) return highest + clearance.get();

        return base;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || ServerUtils.mc().player == null) return;

        if (current != null) drawTarget(event, current, targetColor.get(), 6);

        for (FlightPlanner.Target target : queue) {
            if (target == current) continue;
            drawTarget(event, target, queueColor.get(), 3);
        }
    }

    private void drawTarget(Render3DEvent event, FlightPlanner.Target target, SettingColor color, int radius) {
        double distance = ElytraController.horizontalDistanceTo(target.x(), target.z());
        if (distance > renderDistance.get()) return;

        event.renderer.box(
            target.x() - radius, cruiseY.get() - radius, target.z() - radius,
            target.x() + radius, cruiseY.get() + radius, target.z() + radius,
            color, color, shapeMode.get(), 0
        );
    }

    @Override
    public String getInfoString() {
        if (!started) return "idle";
        return "%d queued, %s".formatted(queue.size(), ElytraManager.hasElytraEquipped() ? "flying" : "no elytra");
    }

    // Accessors for the command and HUD.

    public boolean isStarted() {
        return started;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getQueuedCount() {
        return queue.size();
    }

    public int getTargetsVisited() {
        return targetsVisited;
    }

    public int getElytraSwaps() {
        return elytraSwaps;
    }

    public double getTravelledBlocks() {
        return travelledBlocks;
    }

    public FlightPlanner.Target getCurrentTarget() {
        return current;
    }

    public List<FlightPlanner.Target> getQueuedTargets() {
        return new ArrayList<>(queue);
    }

    /** Selects the sweep pattern, used by {@code /bh explore <mode>}. */
    public void setExploreMode(ExploreMode mode) {
        exploreMode.set(mode);
        patternLeg = 0;
        customRouteDrained = false;
    }

    public ExploreMode getExploreMode() {
        return exploreMode.get();
    }

    /** Restarts the generated pattern from the player's current position. */
    public void resetPattern() {
        patternLeg = 0;
    }

    /** Sets the compass bearing used by heading mode, used by {@code /bh explore heading <degrees>}. */
    public void setHeadingDegrees(double degrees) {
        headingDegrees.set(degrees);
    }

    public double getHeadingDegrees() {
        return headingDegrees.get();
    }

    private int exploredCount() {
        ExplorationStore store = ExplorationStore.get();
        return store == null ? 0 : store.regions().exploredCount();
    }

    /** Forces a queue rebuild, used by {@code /bh v2 refresh}. */
    public void refreshTargets() {
        refreshQueue();
        if (queue.isEmpty()) pushFrontier();
        if (chatFeedback.get()) info("Queue rebuilt: %d targets.", queue.size());
    }
}
