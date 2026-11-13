package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ProximityRadar;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Watches the players around you and tells you when one is closing in.
 *
 * <p>On an anarchy server the difference between "someone is on the server" and "someone is 30 blocks away
 * and approaching" is the difference between continuing a stash run and leaving. This module tracks every
 * nearby player's distance, reports when one enters range, when one is approaching (not merely present) and
 * when one leaves, and pushes each transition onto the automation bus so other modules can react.
 *
 * <p>It reuses the {@code player-logger}'s persistence by recording sightings the same way, but its job is
 * the live picture: a bounded, evicting contact list and a transition classifier from the pure
 * {@link ProximityRadar}. It never attacks anyone - it only observes and reports.
 */
public class PlayerRadar extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> trackRadius = sgGeneral.add(new IntSetting.Builder()
        .name("track-radius")
        .description("Only track players within this many blocks.")
        .defaultValue(128)
        .min(8)
        .sliderRange(8, 512)
        .build()
    );

    private final Setting<Double> approachThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("approach-threshold")
        .description("How many blocks closer a player must get to count as approaching.")
        .defaultValue(4.0)
        .min(0.5)
        .sliderRange(0.5, 32.0)
        .build()
    );

    private final Setting<Boolean> alertEnter = sgGeneral.add(new BoolSetting.Builder()
        .name("alert-on-enter")
        .description("Report a player entering the tracked radius.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> alertApproach = sgGeneral.add(new BoolSetting.Builder()
        .name("alert-on-approach")
        .description("Report a player who is closing on you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print alerts in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> expireSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("expire-seconds")
        .description("Forget a player not seen for this long.")
        .defaultValue(15)
        .min(2)
        .sliderRange(2, 120)
        .build()
    );

    private final ProximityRadar radar = new ProximityRadar(64, 4.0);
    private int tickCounter;

    public PlayerRadar() {
        super(BaseHuntingAddon.CATEGORY, "player-radar",
            "Tracks nearby players and warns when one is approaching.");
    }

    @Override
    public void onActivate() {
        radar.clear();
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Players move slowly enough that 5 ticks is plenty; it also keeps the per-tick cost trivial on a
        // server with dozens of players loaded.
        if (++tickCounter % 5 != 0) return;

        radar.setApproachThreshold(approachThreshold.get());

        long now = System.currentTimeMillis();
        long maxAge = expireSeconds.get() * 1000L;
        int radius = trackRadius.get();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (player.isSpectator()) continue;

            double distance = mc.player.distanceTo(player);
            if (distance > radius) continue;

            ProximityRadar.Transition transition = radar.observe(
                player.getGameProfile().name(),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                distance, now);

            report(transition, player.getGameProfile().name(), distance, player);
        }

        for (String name : radar.expire(now, maxAge)) {
            publish(AutomationEvent.Type.PLAYER_LEFT_RANGE, Map.of("player", name));
        }
    }

    private void report(ProximityRadar.Transition transition, String name, double distance,
                        PlayerEntity player) {
        switch (transition) {
            case ENTERED -> {
                publish(AutomationEvent.Type.PLAYER_NEARBY, Map.of(
                    "player", name,
                    "distance", Integer.toString((int) distance),
                    "coords", "%d, %d, %d".formatted((int) player.getX(), (int) player.getY(),
                        (int) player.getZ())));
                if (alertEnter.get() && chatFeedback.get()) {
                    warning("Player %s is nearby (%.0fm).", name, distance);
                }
            }
            case APPROACHING -> {
                publish(AutomationEvent.Type.PLAYER_APPROACHING, Map.of(
                    "player", name, "distance", Integer.toString((int) distance)));
                if (alertApproach.get() && chatFeedback.get()) {
                    warning("Player %s is approaching (%.0fm).", name, distance);
                }
            }
            default -> {
                // STEADY and RECEDING are not worth an alert.
            }
        }
    }

    /** The nearest tracked player right now, or null. */
    public ProximityRadar.Contact nearest() {
        List<ProximityRadar.Contact> active = radar.active(System.currentTimeMillis(),
            expireSeconds.get() * 1000L);
        return active.isEmpty() ? null : active.get(0);
    }

    public List<ProximityRadar.Contact> nearby() {
        return radar.active(System.currentTimeMillis(), expireSeconds.get() * 1000L);
    }

    public String statusLine() {
        return radar.summary(System.currentTimeMillis(), expireSeconds.get() * 1000L);
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("tracked: " + radar.size());
        for (ProximityRadar.Contact contact : nearby()) {
            lines.add("  %s %s %.0fm".formatted(contact.name(), contact.coords(), contact.distance()));
        }
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
