package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.SurvivalStatus;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Map;

/**
 * Watches the player's survivability and raises the alarm before a problem becomes fatal.
 *
 * <p>This module deliberately does not disconnect on its own. {@link AutoLog} owns that decision, and having
 * two modules fight over when to log out is exactly the kind of conflicting control loop the architecture is
 * meant to avoid. Instead the survival manager publishes events and can ask an active route to pause; AutoLog
 * remains the single authority on disconnecting.
 */
public class SurvivalManager extends Module {
    private static final int EVALUATE_INTERVAL_TICKS = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThresholds = settings.createGroup("Thresholds");
    private final SettingGroup sgActions = settings.createGroup("Actions");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print survival warnings in chat.")
        .defaultValue(true)
        .build()
    );

    // Thresholds

    private final Setting<Integer> minHealth = sgThresholds.add(new IntSetting.Builder()
        .name("min-health")
        .description("Health at or below which the player is considered critical.")
        .defaultValue(8)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> minFood = sgThresholds.add(new IntSetting.Builder()
        .name("min-food")
        .description("Hunger at or below which the player should eat.")
        .defaultValue(6)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> minElytraDurability = sgThresholds.add(new IntSetting.Builder()
        .name("min-elytra-durability")
        .description("Elytra durability at or below which a replacement is needed.")
        .defaultValue(24)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Integer> minSpareElytras = sgThresholds.add(new IntSetting.Builder()
        .name("min-spare-elytras")
        .description("Spare elytras to keep in reserve.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Integer> minTotems = sgThresholds.add(new IntSetting.Builder()
        .name("min-totems")
        .description("Totems of undying to keep in reserve.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Integer> minFoodItems = sgThresholds.add(new IntSetting.Builder()
        .name("min-food-items")
        .description("Food items to keep in reserve.")
        .defaultValue(8)
        .min(0)
        .sliderRange(0, 128)
        .build()
    );

    private final Setting<Integer> minFireworks = sgThresholds.add(new IntSetting.Builder()
        .name("min-fireworks")
        .description("Fireworks to keep while gliding, when fireworks are used.")
        .defaultValue(16)
        .min(0)
        .sliderRange(0, 128)
        .build()
    );

    private final Setting<Integer> minFreeSlots = sgThresholds.add(new IntSetting.Builder()
        .name("min-free-slots")
        .description("Free inventory slots below which the inventory is treated as full.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 36)
        .build()
    );

    // Actions

    private final Setting<Boolean> pauseRouteOnCritical = sgActions.add(new BoolSetting.Builder()
        .name("pause-route-on-critical")
        .description("Pause an active base-hunter route when a critical condition is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> warningCooldownSeconds = sgActions.add(new IntSetting.Builder()
        .name("warning-cooldown-seconds")
        .description("Minimum time between repeat warnings for the same condition.")
        .defaultValue(30)
        .min(1)
        .sliderRange(1, 600)
        .build()
    );

    // State

    private int tickCounter;
    private SurvivalStatus.Snapshot snapshot;
    private List<SurvivalStatus.Issue> issues = List.of();
    private final java.util.Map<String, Long> lastWarning = new java.util.HashMap<>();

    public SurvivalManager() {
        super(BaseHuntingAddon.CATEGORY, "survival-manager",
            "Monitors health, hunger, equipment and reserves, and warns before a situation turns fatal.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        snapshot = null;
        issues = List.of();
        lastWarning.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        tickCounter = EVALUATE_INTERVAL_TICKS;

        snapshot = capture();
        issues = SurvivalStatus.evaluate(snapshot, thresholds());

        reportIssues();

        if (SurvivalStatus.hasCritical(issues)) handleCritical();
    }

    /** Builds the current snapshot from live game state. */
    public SurvivalStatus.Snapshot capture() {
        PlayerInventory inventory = mc.player.getInventory();

        int spareElytras = 0;
        int totems = 0;
        int foodItems = 0;
        int fireworks = 0;
        int freeSlots = 0;

        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                freeSlots++;
                continue;
            }

            if (stack.getItem() == Items.ELYTRA) spareElytras++;
            else if (stack.getItem() == Items.TOTEM_OF_UNDYING) totems += stack.getCount();
            else if (stack.getItem() == Items.FIREWORK_ROCKET) fireworks += stack.getCount();
            else if (stack.getItem().getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                foodItems += stack.getCount();
            }
        }

        boolean hasElytra = ElytraManager.hasElytraEquipped();

        return new SurvivalStatus.Snapshot(
            mc.player.getHealth(),
            mc.player.getMaxHealth(),
            mc.player.getHungerManager().getFoodLevel(),
            hasElytra ? ElytraManager.equippedRemaining() : -1,
            spareElytras,
            totems,
            foodItems,
            fireworks,
            freeSlots,
            mc.player.isGliding(),
            hasElytra
        );
    }

    private SurvivalStatus.Thresholds thresholds() {
        return new SurvivalStatus.Thresholds(
            minHealth.get(),
            minFood.get(),
            minElytraDurability.get(),
            minSpareElytras.get(),
            minTotems.get(),
            minFoodItems.get(),
            minFireworks.get(),
            minFreeSlots.get()
        );
    }

    private void reportIssues() {
        long now = java.lang.System.currentTimeMillis();
        long cooldown = warningCooldownSeconds.get() * 1000L;

        for (SurvivalStatus.Issue issue : issues) {
            Long last = lastWarning.get(issue.code());
            if (last != null && now - last < cooldown) continue;
            lastWarning.put(issue.code(), now);

            if (chatFeedback.get()) warning("Survival: %s.", issue.description());
            publish(eventTypeFor(issue.code()), Map.of("issue", issue.code(), "detail", issue.description()));
        }
    }

    private void handleCritical() {
        if (!pauseRouteOnCritical.get()) return;

        BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
        if (v2 != null && v2.isActive()) {
            // Pausing rather than stopping keeps the target, so the route can resume once the danger passes.
            v2.pause();
            publish(AutomationEvent.Type.DANGER_DETECTED, Map.of("action", "route paused"));
        }
    }

    private AutomationEvent.Type eventTypeFor(String code) {
        return switch (code) {
            case "health" -> AutomationEvent.Type.HEALTH_CRITICAL;
            case "hunger" -> AutomationEvent.Type.HUNGER_CRITICAL;
            case "elytra-durability" -> AutomationEvent.Type.ELYTRA_DURABILITY_LOW;
            case "elytra-missing" -> AutomationEvent.Type.ELYTRA_MISSING;
            case "inventory-full" -> AutomationEvent.Type.INVENTORY_FULL;
            default -> AutomationEvent.Type.DANGER_DETECTED;
        };
    }

    /** The latest issues, for the HUD and the status command. */
    public List<SurvivalStatus.Issue> issues() {
        return issues;
    }

    public SurvivalStatus.Snapshot snapshot() {
        return snapshot;
    }

    public boolean isHealthy() {
        return !SurvivalStatus.hasCritical(issues);
    }

    /** One-line summary for the HUD. */
    public String statusLine() {
        if (snapshot == null) return "no data";
        if (issues.isEmpty()) return "all good";

        SurvivalStatus.Issue worst = issues.get(0);
        return issues.size() == 1 ? worst.description() : "%s (+%d more)".formatted(worst.description(), issues.size() - 1);
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
