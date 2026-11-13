package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.logic.BehaviorProfiler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Watches the last few seconds of movement and scores how human it looks.
 *
 * <p>The smooth-look camera and the human-behavior gestures are only useful if they actually make the
 * movement read as human. This module closes the loop: it samples yaw and position every tick and reduces
 * the window to a 0-100 score with the pure {@link BehaviorProfiler}, so a flat 0 ("this looks like a bot")
 * is visible on the HUD and in {@code /bh human status} rather than being taken on faith.
 *
 * <p>It is read-only: it never writes the camera or the player, so it can run alongside anything.
 */
public class BehaviorMonitor extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> windowTicks = sgGeneral.add(new IntSetting.Builder()
        .name("window-ticks")
        .description("How many ticks of history the score is computed over.")
        .defaultValue(100)
        .min(20)
        .sliderRange(20, 400)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Warn in chat when the movement has read as robotic for a while.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> warningThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Warn when the human-likeness score falls below this.")
        .defaultValue(25)
        .min(0)
        .sliderRange(0, 100)
        .visible(chatFeedback::get)
        .build()
    );

    private BehaviorProfiler profiler = new BehaviorProfiler(100);
    private int configuredWindow = -1;
    private int lowStreak;
    private boolean warned;

    public BehaviorMonitor() {
        super(BaseHuntingAddon.CATEGORY, "behavior-monitor",
            "Scores how human the recent movement and camera look.");
    }

    @Override
    public void onActivate() {
        configuredWindow = -1;
        lowStreak = 0;
        warned = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        int window = windowTicks.get();
        if (window != configuredWindow) {
            profiler = new BehaviorProfiler(window);
            configuredWindow = window;
        }

        double x = mc.player.getX();
        double z = mc.player.getZ();
        boolean moved = mc.player.getVelocity().horizontalLengthSquared() > 0.001;

        profiler.sample(mc.player.getYaw(), mc.player.getPitch(), x, z, moved);

        if (!chatFeedback.get() || profiler.samples() < window) return;

        if (profiler.humanLikeness() < warningThreshold.get()) {
            if (++lowStreak == 200 && !warned) {
                warning("Movement has read as robotic for a while (%s).", profiler.report().describe());
                warned = true;
            }
        } else {
            lowStreak = 0;
            warned = false;
        }
    }

    public BehaviorProfiler profiler() {
        return profiler;
    }

    public double humanLikeness() {
        return profiler.humanLikeness();
    }

    public String statusLine() {
        if (profiler.samples() < 5) return "measuring";
        return "%.0f%% human".formatted(profiler.humanLikeness());
    }

    public String describe() {
        return profiler.report().describe();
    }
}
