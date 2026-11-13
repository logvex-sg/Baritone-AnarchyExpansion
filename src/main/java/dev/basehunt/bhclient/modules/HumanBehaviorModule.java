package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.HumanBehavior;
import dev.basehunt.bhclient.core.logic.LookSmoother;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;

import java.util.Map;
import java.util.Set;

/**
 * Adds the small, purposeless movement of a real player so an idling client does not read as a bot.
 *
 * <p>Standing perfectly still, at a constant angle, never jumping or sneaking, is as distinctive as snapping
 * the camera. This module schedules short, bounded gestures - a glance, a tiny strafe, a jump, a brief
 * sneak, a look down at the hotbar - separated by random pauses, using the pure {@link HumanBehavior}. It is
 * deliberately timid: the gestures are short, it refuses to gesture while the player is already moving under
 * another automation, and it takes the lowest automation priority so it never preempts anything that
 * matters.
 *
 * <p>It only presses the same movement keys a player would and eases the camera the way {@link LookSmoother}
 * does; it forges no packets and sends no chat.
 */
public class HumanBehaviorModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minIdle = sgGeneral.add(new IntSetting.Builder()
        .name("min-idle-ticks")
        .description("Shortest pause between gestures, in ticks.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 400)
        .build()
    );

    private final Setting<Integer> maxIdle = sgGeneral.add(new IntSetting.Builder()
        .name("max-idle-ticks")
        .description("Longest pause between gestures, in ticks.")
        .defaultValue(120)
        .min(1)
        .sliderRange(1, 1200)
        .build()
    );

    private final Setting<Integer> minGesture = sgGeneral.add(new IntSetting.Builder()
        .name("min-gesture-ticks")
        .description("Shortest gesture duration, in ticks.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> maxGesture = sgGeneral.add(new IntSetting.Builder()
        .name("max-gesture-ticks")
        .description("Longest gesture duration, in ticks.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Integer> glanceRange = sgGeneral.add(new IntSetting.Builder()
        .name("glance-range")
        .description("Maximum degrees a glance turns the view.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 45)
        .build()
    );

    private final Setting<Boolean> allowJump = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-jump")
        .description("Allow an occasional jump.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-sneak")
        .description("Allow a brief sneak.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-moving")
        .description("Do not gesture while the player is already moving under another automation.")
        .defaultValue(true)
        .build()
    );

    private final HumanBehavior behavior = new HumanBehavior(0L);

    private float baseYaw;
    private float basePitch;
    private float glanceTarget;
    private float glanceOffset;
    private float pitchTarget;
    private float pitchOffset;
    private boolean holdingLease;

    public HumanBehaviorModule() {
        super(BaseHuntingAddon.CATEGORY, "human-behavior",
            "Idles with the small, irregular movements of a real player.");
    }

    @Override
    public void onActivate() {
        configure();
        behavior.reset();
        baseYaw = mc.player == null ? 0.0f : mc.player.getYaw();
        basePitch = mc.player == null ? 0.0f : mc.player.getPitch();
        glanceTarget = glanceOffset = pitchTarget = pitchOffset = 0.0f;
        holdingLease = false;
    }

    @Override
    public void onDeactivate() {
        releaseKeys();
        releaseLease();
    }

    public long gestures() {
        return behavior.gestures();
    }

    public boolean isGesturing() {
        return behavior.busy();
    }

    public String statusLine() {
        HumanBehavior.Gesture active = behavior.active();
        if (active == null) return "idle";
        return "gesture: " + active.kind().name().toLowerCase();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        configure();

        if (pauseWhenMoving.get() && moving()) {
            behavior.reset();
            releaseKeys();
            releaseLease();
            return;
        }

        if (!acquireLease()) return;

        HumanBehavior.Gesture started = behavior.tick();
        if (started != null) {
            beginGesture(started);
            publish(AutomationEvent.Type.HUMAN_GESTURE, Map.of(
                "kind", started.kind().name().toLowerCase(),
                "ticks", Integer.toString(started.durationTicks())));
        }

        applyCamera();
    }

    private void beginGesture(HumanBehavior.Gesture gesture) {
        releaseKeys();
        baseYaw = mc.player.getYaw();
        basePitch = mc.player.getPitch();

        switch (gesture.kind()) {
            case GLANCE -> glanceTarget = gesture.value();
            case GLANCE_DOWN -> pitchTarget = 18.0f;
            case JUMP -> mc.options.jumpKey.setPressed(true);
            case SNEAK -> mc.options.sneakKey.setPressed(true);
            case STRAFE -> (gesture.value() > 0 ? mc.options.leftKey : mc.options.rightKey).setPressed(true);
        }
    }

    /** Eases the glance back toward the resting angle and writes the camera only while it is off-centre. */
    private void applyCamera() {
        boolean busy = behavior.busy();
        if (!busy) {
            glanceTarget = 0.0f;
            pitchTarget = 0.0f;
        }

        glanceOffset += (glanceTarget - glanceOffset) * 0.25f;
        pitchOffset += (pitchTarget - pitchOffset) * 0.25f;

        boolean offCentre = Math.abs(glanceOffset) > 0.05f || Math.abs(pitchOffset) > 0.05f;
        if (busy || offCentre) {
            float yaw = LookSmoother.wrap(baseYaw + glanceOffset);
            mc.player.setYaw(yaw);
            mc.player.setHeadYaw(yaw);
            mc.player.setPitch(LookSmoother.clampPitch(basePitch + pitchOffset));
        }

        if (!busy) releaseKeys();
    }

    private void configure() {
        behavior.configure(minIdle.get(), maxIdle.get(), minGesture.get(), maxGesture.get(),
            glanceRange.get(), allowJump.get(), allowSneak.get());
    }

    private void releaseKeys() {
        for (KeyBinding key : new KeyBinding[] {
            mc.options.jumpKey, mc.options.sneakKey, mc.options.leftKey, mc.options.rightKey
        }) {
            key.setPressed(false);
        }
    }

    /** Another automation is moving the player, so a gesture would only fight it. */
    private boolean moving() {
        if (mc.player.getVelocity().horizontalLengthSquared() > 0.003) return true;

        AutomationManager manager = AutomationManager.get();
        if (manager == null) return false;

        String owner = manager.scheduler().ownerOf(ControlResource.MOVEMENT);
        return owner != null && !name.equals(owner);
    }

    private boolean acquireLease() {
        AutomationManager manager = AutomationManager.get();
        if (manager == null) return true;

        if (manager.tryAcquire(name, Priority.BACKGROUND_DISCOVERY,
                Set.of(ControlResource.MOVEMENT, ControlResource.ROTATION)) == null) {
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

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
