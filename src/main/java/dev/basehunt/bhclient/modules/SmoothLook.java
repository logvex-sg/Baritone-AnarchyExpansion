package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.LookSmoother;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.Set;

/**
 * Turns the camera the way a person does: over several ticks, easing in, never snapping.
 *
 * <p>An instant, perfectly precise snap to a target is the clearest sign that a client is automated. This
 * module exists so an aim request - from a command, from a task, or from the {@code face-player} setting -
 * is approached along the shortest arc at a bounded angular speed with a little settling jitter, instead of
 * being written straight to the player. The maths live in the pure {@link LookSmoother}.
 *
 * <p>It takes the {@link ControlResource#ROTATION} lease while it is turning, so it never fights another
 * module for the camera: if something with a higher priority is aiming, this one simply waits. That is the
 * whole point of routing rotation through the scheduler rather than writing it directly.
 */
public class SmoothLook extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Maximum degrees the camera turns per tick. Lower is smoother and slower.")
        .defaultValue(12.0)
        .min(1.0)
        .sliderRange(1.0, 45.0)
        .build()
    );

    private final Setting<Double> easing = sgGeneral.add(new DoubleSetting.Builder()
        .name("easing")
        .description("How much of the remaining angle is covered each tick (0-1). Lower eases in more.")
        .defaultValue(0.35)
        .min(0.02)
        .max(1.0)
        .sliderRange(0.02, 1.0)
        .build()
    );

    private final Setting<Double> jitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("jitter")
        .description("Degrees of settling wobble, strongest mid-turn. 0 is perfectly mechanical.")
        .defaultValue(0.8)
        .min(0.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    private final Setting<Double> tolerance = sgGeneral.add(new DoubleSetting.Builder()
        .name("tolerance")
        .description("How close to the target counts as aimed (degrees).")
        .defaultValue(1.5)
        .min(0.1)
        .sliderRange(0.1, 10.0)
        .build()
    );

    private final Setting<Boolean> facePlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("face-player")
        .description("Continuously look at the nearest player within range (smoothly).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> faceRadius = sgGeneral.add(new IntSetting.Builder()
        .name("face-radius")
        .description("Only face a player within this many blocks.")
        .defaultValue(24)
        .min(2)
        .sliderRange(2, 128)
        .visible(facePlayer::get)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Report in chat when a look target is reached.")
        .defaultValue(false)
        .build()
    );

    private final LookSmoother smoother = new LookSmoother(0L);
    private boolean hasTarget;
    private String targetLabel = "";
    private boolean holdingLease;

    public SmoothLook() {
        super(BaseHuntingAddon.CATEGORY, "smooth-look",
            "Turns the camera smoothly toward a target instead of snapping to it.");
    }

    @Override
    public void onActivate() {
        hasTarget = false;
        targetLabel = "";
        holdingLease = false;
        if (mc.player != null) smoother.snap(mc.player.getYaw(), mc.player.getPitch());
    }

    @Override
    public void onDeactivate() {
        releaseLease();
        hasTarget = false;
    }

    /** Smoothly looks at a world position. Returns false when there is no player to turn. */
    public boolean lookAt(double x, double y, double z) {
        if (mc.player == null) return false;

        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();

        smoother.aimAt(
            LookSmoother.yawTo(ex, ez, x, z),
            LookSmoother.pitchTo(ex, ey, ez, x, y, z));

        hasTarget = true;
        targetLabel = "%.0f %.0f %.0f".formatted(x, y, z);
        return true;
    }

    /** Smoothly looks at a raw yaw/pitch. Returns false when there is no player to turn. */
    public boolean look(float yaw, float pitch) {
        if (mc.player == null) return false;

        smoother.aimAt(yaw, pitch);
        hasTarget = true;
        targetLabel = "yaw %.0f pitch %.0f".formatted(yaw, pitch);
        return true;
    }

    /** Abandons the current look target. */
    public void clearTarget() {
        hasTarget = false;
        targetLabel = "";
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public boolean isTurning() {
        return hasTarget && !smoother.atTarget((float) (double) tolerance.get());
    }

    public String statusLine() {
        if (!hasTarget) return facePlayer.get() ? "facing nearby players" : "idle";
        return "turning to " + targetLabel;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        smoother.setSpeed((float) (double) speed.get());
        smoother.setEasing((float) (double) easing.get());
        smoother.setJitter((float) (double) jitter.get());

        if (facePlayer.get() && !hasTarget) {
            PlayerEntity nearest = nearestPlayer(faceRadius.get());
            if (nearest != null) {
                lookAt(nearest.getX(), nearest.getEyeY(), nearest.getZ());
            }
        }

        if (!hasTarget) {
            releaseLease();
            return;
        }

        if (!acquireLease()) return;

        LookSmoother.Step step = smoother.tick();
        apply(step);

        if (smoother.atTarget((float) (double) tolerance.get())) {
            // A continuous face-player keeps its target; a one-shot look completes and releases the camera.
            if (facePlayer.get()) return;

            hasTarget = false;
            String label = targetLabel;
            targetLabel = "";
            releaseLease();
            publish(AutomationEvent.Type.LOOK_COMPLETED, Map.of("target", label));
            if (chatFeedback.get()) info("Look target reached (%s).", label);
        }
    }

    private void apply(LookSmoother.Step step) {
        mc.player.setYaw(step.yaw());
        mc.player.setHeadYaw(step.yaw());
        mc.player.setPitch(step.pitch());
    }

    private boolean acquireLease() {
        AutomationManager manager = AutomationManager.get();
        if (manager == null) return true;

        if (manager.tryAcquire(name, Priority.TARGET_INVESTIGATION, Set.of(ControlResource.ROTATION)) == null) {
            return false;
        }
        holdingLease = true;
        return true;
    }

    private void releaseLease() {
        if (!holdingLease) return;
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.releaseAll(name);
        holdingLease = false;
    }

    private PlayerEntity nearestPlayer(double radius) {
        PlayerEntity best = null;
        double bestDistance = radius;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            double distance = mc.player.distanceTo(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
