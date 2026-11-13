package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.BaritoneTaskCatalog;
import dev.basehunt.bhclient.core.logic.BaritoneTaskCatalog.Kind;
import dev.basehunt.bhclient.core.logic.BaritoneTaskCatalog.Request;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives Baritone's own processes - mine, follow, explore, get-to-block, farm and build - so an anarchy
 * session can automate gathering and travel, not just base hunting.
 *
 * <p>Baritone already knows how to do the hard part (pathing to and mining the right block, following an
 * entity, exploring chunks, building from a schematic). What it lacks is a single place to start a job,
 * remember that it is running, notice when it finishes or fails, and report that as an automation event the
 * rest of the addon can react to. This module is that place.
 *
 * <p>The module never touches a {@code baritone.*} type directly: every call goes through
 * {@link BaritoneHelper}, which probes for the API without initialising it, so the addon still loads when
 * Baritone is absent. Every request is parsed by the pure {@link BaritoneTaskCatalog}, so a malformed job is
 * refused with a reason instead of being handed to Baritone as a no-op.
 */
public class BaritoneControl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAuto = settings.createGroup("Automation");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Report job start, completion and failure in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopOnDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-death")
        .description("Cancel the running job when you die, so the bot does not keep mining as a ghost.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoPickup = sgAuto.add(new BoolSetting.Builder()
        .name("auto-pickup")
        .description("While mining, also walk over and pick up the drops.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> watchJobs = sgAuto.add(new BoolSetting.Builder()
        .name("watch-jobs")
        .description("Notice when a job finishes or Baritone loses control and report it once.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> idleGraceTicks = sgAuto.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
        .name("idle-grace-ticks")
        .description("How long Baritone may report nothing moving before a job is declared finished or failed.")
        .defaultValue(40)
        .min(10)
        .sliderRange(10, 200)
        .visible(watchJobs::get)
        .build()
    );

    private final Setting<List<String>> queuedJobs = sgGeneral.add(new StringListSetting.Builder()
        .name("queued-jobs")
        .description("Jobs to run in order, one line each (e.g. 'mine diamond_ore 64').")
        .defaultValue(List.of())
        .build()
    );

    private Kind activeKind;
    private String activeLabel = "";
    private boolean active;
    private int idleTicks;
    private long startedAt;
    private int completed;
    private int failed;

    public BaritoneControl() {
        super(BaseHuntingAddon.CATEGORY, "baritone-control",
            "Runs Baritone jobs (mine, follow, explore, goto, farm, build) and reports them as events.");
    }

    @Override
    public void onActivate() {
        active = false;
        activeKind = null;
        activeLabel = "";
        idleTicks = 0;
    }

    @Override
    public void onDeactivate() {
        cancel();
    }

    /**
     * Parses and starts a job from a raw line, e.g. {@code mine diamond_ore 64} or {@code goto 100 64 -200}.
     *
     * @return a short result string for chat and the terminal
     */
    public String run(String line) {
        Request request = BaritoneTaskCatalog.parseLine(line);
        return run(request);
    }

    /** Starts a parsed request. */
    public String run(Request request) {
        if (request == null || !request.valid()) {
            String reason = request == null ? "no task given" : request.error();
            if (chatFeedback.get()) warning("Baritone job refused: %s", reason);
            return "refused: " + reason;
        }

        if (!BaritoneHelper.usable()) {
            String health = BaritoneHelper.health();
            String reason = health.isEmpty() ? "Baritone is not installed" : "Baritone is unusable: " + health;
            if (chatFeedback.get()) warning("Cannot run the job: %s", reason);
            return "unavailable: " + reason;
        }

        cancel();

        boolean started = dispatch(request);
        if (!started) {
            failed++;
            publish(AutomationEvent.Type.BARITONE_TASK_FAILED, Map.of("task", request.describe()));
            if (chatFeedback.get()) warning("Baritone refused the job: %s", request.describe());
            return "failed: " + request.describe();
        }

        active = true;
        activeKind = request.kind();
        activeLabel = request.describe();
        idleTicks = 0;
        startedAt = System.currentTimeMillis();

        publish(AutomationEvent.Type.BARITONE_TASK_STARTED, Map.of("task", activeLabel));
        if (chatFeedback.get()) info("Baritone job started: %s", activeLabel);
        return "started: " + activeLabel;
    }

    private boolean dispatch(Request request) {
        return switch (request.kind()) {
            case MINE -> {
                if (autoPickup.get()) BaritoneHelper.pickupItems(stack -> true);
                yield BaritoneHelper.mine(request.target(), request.quantity());
            }
            case GOTO -> BaritoneHelper.gotoBlock(request.x(), request.y(), request.z());
            case FOLLOW -> BaritoneHelper.follow(request.target());
            case EXPLORE -> BaritoneHelper.explore(request.x() >> 4, request.z() >> 4);
            case FARM -> BaritoneHelper.farm(request.quantity());
            case BUILD -> BaritoneHelper.build(request.target(), request.x(), request.y(), request.z());
            case TUNNEL -> BaritoneHelper.tunnel(0);
            case STOP -> {
                cancel();
                yield true;
            }
        };
    }

    /** Cancels the active job and every Baritone process. Safe to call when nothing is running. */
    public void cancel() {
        if (!active) {
            BaritoneHelper.cancelAllProcesses();
            return;
        }

        String label = activeLabel;
        active = false;
        activeKind = null;
        activeLabel = "";
        idleTicks = 0;

        BaritoneHelper.cancelAllProcesses();
        publish(AutomationEvent.Type.BARITONE_TASK_CANCELLED, Map.of("task", label));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!active) return;
        if (mc.player == null || mc.world == null) return;

        if (!BaritoneHelper.usable()) {
            active = false;
            failed++;
            publish(AutomationEvent.Type.BARITONE_TASK_FAILED, Map.of("task", activeLabel, "reason", "Baritone went away"));
            if (chatFeedback.get()) warning("Baritone became unavailable; job %s stopped.", activeLabel);
            return;
        }

        // Follow is a steady state: Baritone keeps tracking until we stop it, so it is never "idle".
        if (activeKind == Kind.FOLLOW) return;

        if (BaritoneHelper.isMoving()) {
            idleTicks = 0;
            return;
        }

        if (++idleTicks < idleGraceTicks.get()) return;

        // Baritone stopped moving without us cancelling: the job is over (done or given up).
        String label = activeLabel;
        long elapsed = System.currentTimeMillis() - startedAt;
        active = false;
        activeKind = null;
        activeLabel = "";
        idleTicks = 0;

        if (watchJobs.get()) {
            completed++;
            publish(AutomationEvent.Type.BARITONE_TASK_DONE,
                Map.of("task", label, "seconds", Long.toString(elapsed / 1000)));
            if (chatFeedback.get()) info("Baritone job finished: %s (%ds)", label, elapsed / 1000);
        }
    }

    /** Starts the next job from the queued-jobs setting, if any are listed and nothing is running. */
    public String runNextQueued() {
        List<String> jobs = queuedJobs.get();
        if (jobs == null || jobs.isEmpty()) return "no queued jobs";

        for (String job : jobs) {
            Request request = BaritoneTaskCatalog.parseLine(job);
            if (request.valid()) return run(request);
        }
        return "no valid queued jobs";
    }

    public boolean isRunning() {
        return active;
    }

    public Kind activeKind() {
        return activeKind;
    }

    public int completed() {
        return completed;
    }

    public int failed() {
        return failed;
    }

    public String statusLine() {
        if (!active) return BaritoneHelper.usable() ? "idle" : "Baritone unavailable";
        return activeLabel;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(statusLine());
        lines.add("completed: %d, failed: %d".formatted(completed, failed));
        lines.add("queued jobs: %d".formatted(queuedJobs.get() == null ? 0 : queuedJobs.get().size()));
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
