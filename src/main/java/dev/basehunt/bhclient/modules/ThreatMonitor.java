package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ThreatAssessment;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns "there is a player nearby" into "how much trouble am I in".
 *
 * <p>The {@code player-radar} module tells you a player is close; it deliberately says nothing about whether
 * that player is a threat. This module reads the same contacts but scores each one using the pure
 * {@link ThreatAssessment} - distance, how fast they are closing, and what they are wearing and holding - and
 * raises the alarm only when the score says it is warranted. A leather-clad player drifting past is CALM; a
 * totem-carrying, netherite-wearing player closing at speed is FLEE.
 *
 * <p>It is a pure observer. It moves nothing, attacks nobody, and only publishes events and (optionally)
 * prints to chat, so it can sit alongside every other module without competing for a resource.
 */
public class ThreatMonitor extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScoring = settings.createGroup("Scoring");

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Only assess players within this many blocks.")
        .defaultValue(64)
        .min(8)
        .sliderRange(8, 256)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print an alert when a player becomes a real threat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> alertCooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("alert-cooldown-seconds")
        .description("Minimum time between repeat alerts for the same player.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 300)
        .build()
    );

    private final Setting<Double> closeDistance = sgScoring.add(new DoubleSetting.Builder()
        .name("close-distance")
        .description("Distance at which proximity dominates the score.")
        .defaultValue(16.0)
        .min(2.0)
        .sliderRange(2.0, 64.0)
        .build()
    );

    private final Setting<Double> approachThreshold = sgScoring.add(new DoubleSetting.Builder()
        .name("approach-threshold")
        .description("Blocks per second a player must close to count as approaching.")
        .defaultValue(3.0)
        .min(0.1)
        .sliderRange(0.1, 32.0)
        .build()
    );

    private final ThreatAssessment.Params params = ThreatAssessment.Params.defaults();
    private final Map<String, Long> lastAlert = new java.util.HashMap<>();
    private List<ThreatAssessment.Threat> threats = List.of();
    private ThreatAssessment.Level worstLevel = ThreatAssessment.Level.CALM;
    private int tickCounter;

    public ThreatMonitor() {
        super(BaseHuntingAddon.CATEGORY, "threat-monitor",
            "Scores nearby players by how dangerous they are and raises the alarm when it matters.");
    }

    @Override
    public void onActivate() {
        lastAlert.clear();
        threats = List.of();
        worstLevel = ThreatAssessment.Level.CALM;
        tickCounter = 0;
        lastDistances.clear();
    }

    private final Map<String, Sample> lastDistances = new java.util.HashMap<>();

    private record Sample(double distance, long time) {
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Two ticks between full assessments is enough for how fast a person closes, and it keeps the cost
        // trivial on a server with dozens of players loaded.
        if (++tickCounter % 2 != 0) return;

        long now = System.currentTimeMillis();
        ThreatAssessment.Params tuned = tuned();
        int radius = scanRadius.get();

        List<ThreatAssessment.Threat> found = new ArrayList<>();
        ThreatAssessment.Level worst = ThreatAssessment.Level.CALM;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;

            double distance = mc.player.distanceTo(player);
            if (distance > radius) continue;

            String name = player.getGameProfile().name();
            double rate = approachRate(name, distance, now);
            ThreatAssessment.Threat threat = ThreatAssessment.assess(name, distance, rate,
                profile(player), tuned);

            found.add(threat);
            if (threat.level().ordinal() > worst.ordinal()) worst = threat.level();

            if (ThreatAssessment.isAlarming(threat.level())) alert(threat, now);
        }

        found.sort(Comparator.comparingInt(ThreatAssessment.Threat::score).reversed());
        threats = found;

        if (worst != worstLevel) {
            worstLevel = worst;
            publish(AutomationEvent.Type.THREAT_LEVEL_CHANGED, Map.of(
                "level", worst.name().toLowerCase(),
                "nearest", nearestName()));
        }
    }

    /** Blocks per second the contact closed since the previous assessment (negative when receding). */
    private double approachRate(String name, double distance, long now) {
        Sample previous = lastDistances.get(name);
        lastDistances.put(name, new Sample(distance, now));
        if (previous == null) return 0.0;

        double seconds = Math.max(0.05, (now - previous.time()) / 1000.0);
        return (previous.distance() - distance) / seconds;
    }

    private ThreatAssessment.Profile profile(PlayerEntity player) {
        int armor = armorPoints(player);
        ItemStack main = player.getMainHandStack();
        boolean weapon = main.getItem().getComponents().contains(net.minecraft.component.DataComponentTypes.WEAPON);
        boolean totem = isTotem(player.getMainHandStack()) || isTotem(player.getOffHandStack());
        return new ThreatAssessment.Profile(armor, weapon, totem, player.isGliding());
    }

    private boolean isTotem(ItemStack stack) {
        return stack != null && stack.isOf(Items.TOTEM_OF_UNDYING);
    }

    /** A rough armour value: 2 points per worn piece, 3 more when the piece is clearly metal-tier. */
    private int armorPoints(PlayerEntity player) {
        int points = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack.isEmpty()) continue;
            points += 2;
            if (stack.getItem() == Items.NETHERITE_CHESTPLATE
                || stack.getItem() == Items.DIAMOND_CHESTPLATE
                || stack.getItem() == Items.NETHERITE_HELMET
                || stack.getItem() == Items.DIAMOND_HELMET) {
                points += 1;
            }
        }
        return Math.min(20, points + 4);
    }

    private void alert(ThreatAssessment.Threat threat, long now) {
        long cooldown = alertCooldownSeconds.get() * 1000L;
        Long last = lastAlert.get(threat.name());
        if (last != null && now - last < cooldown) return;
        lastAlert.put(threat.name(), now);

        publish(AutomationEvent.Type.THREAT_DETECTED, Map.of(
            "player", threat.name(),
            "level", threat.level().name().toLowerCase(),
            "distance", Integer.toString((int) threat.distance()),
            "score", Integer.toString(threat.score())));

        if (chatFeedback.get()) {
            warning("Threat: %s is %s (%.0fm, score %d).", threat.name(),
                threat.level().name().toLowerCase(), threat.distance(), threat.score());
        }
    }

    private ThreatAssessment.Params tuned() {
        return new ThreatAssessment.Params(closeDistance.get(), approachThreshold.get(),
            params.gearWeight(), params.approachWeight(), params.totemWeight(), params.weaponWeight());
    }

    private String nearestName() {
        return threats.isEmpty() ? "none" : threats.get(0).name();
    }

    /** The current threats, worst first. */
    public List<ThreatAssessment.Threat> threats() {
        return threats;
    }

    public ThreatAssessment.Level worstLevel() {
        return worstLevel;
    }

    public boolean isThreatened() {
        return ThreatAssessment.isAlarming(worstLevel);
    }

    /** One-line summary for the HUD. */
    public String statusLine() {
        if (threats.isEmpty()) return "clear";
        ThreatAssessment.Threat worst = threats.get(0);
        return "%s %.0fm (%s)".formatted(worst.name(), worst.distance(),
            worst.level().name().toLowerCase());
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (threats.isEmpty()) {
            lines.add("no threats in range");
            return lines;
        }
        for (ThreatAssessment.Threat threat : threats) lines.add(threat.describe());
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
