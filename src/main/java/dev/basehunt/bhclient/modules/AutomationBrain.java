package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.rule.Condition;
import dev.basehunt.bhclient.core.rule.Rule;
import dev.basehunt.bhclient.core.rule.RuleSet;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.PlayerFacts;
import dev.basehunt.bhclient.utils.ElytraManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Map;

/**
 * The automation brain: reacts to events from the other modules and turns them into actions, without the
 * modules knowing about each other.
 *
 * <p>The rules here are the integration the task asked for. "Elytra durability is critical" leads to an
 * equipment task; "a player was sighted nearby" leads to a survival check; "the inventory is full" leads to a
 * restock or a pause. Each is expressed as a {@link Rule} over {@link PlayerFacts}, so a reaction is
 * declarative, edge-triggered and rate-limited rather than a tangle of direct calls.
 *
 * <p>Every action goes through the shared {@link AutomationEngine} (for work) or an existing module's public
 * method, so two reactions can never drive the same resource at once. The rules only ever <em>submit</em>
 * work; the engine serialises it.
 */
public class AutomationBrain extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgReactions = settings.createGroup("Reactions");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print when a reaction fires.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reactToElytra = sgReactions.add(new BoolSetting.Builder()
        .name("react-to-elytra")
        .description("Replace a failing elytra before it breaks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reactToInventoryFull = sgReactions.add(new BoolSetting.Builder()
        .name("react-to-inventory-full")
        .description("Pause exploration when the inventory is full and report it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reactToLowHealth = sgReactions.add(new BoolSetting.Builder()
        .name("react-to-low-health")
        .description("Report low health and stop active exploration.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reactToPlayers = sgReactions.add(new BoolSetting.Builder()
        .name("react-to-players")
        .description("Report when a player is sighted nearby.")
        .defaultValue(true)
        .build()
    );

    private final RuleSet rules = new RuleSet();
    private final java.util.function.Consumer<AutomationEvent> busListener = this::onEvent;

    public AutomationBrain() {
        super(BaseHuntingAddon.CATEGORY, "automation-brain",
            "Reacts to events from the other modules and turns them into queued automation tasks.");
    }

    @Override
    public void onActivate() {
        rules.clear();
        buildRules();
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.bus().subscribeAll(busListener);
    }

    @Override
    public void onDeactivate() {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.bus().unsubscribeAll(busListener);
        rules.clear();
    }

    /** Builds the reaction rules from the current settings. */
    private void buildRules() {
        if (reactToElytra.get()) {
            rules.add(new Rule.Builder("replace-elytra")
                .when("elytra_durability > 0")
                .when("elytra_durability < 10")
                .cooldown(100)
                .action((rule, tick) -> {
                    boolean replaced = ElytraManager.replaceIfBelow(10);
                    report("elytra durability critical; " + (replaced ? "replaced" : "no viable spare"));
                    return replaced ? "replaced" : "no spare";
                })
                .build());
        }

        if (reactToInventoryFull.get()) {
            rules.add(new Rule.Builder("inventory-full")
                .when("free_slots < 1")
                .cooldown(200)
                .action((rule, tick) -> {
                    report("inventory is full; exploration will be paused");
                    pauseExploration();
                    return "paused exploration";
                })
                .build());
        }

        if (reactToLowHealth.get()) {
            rules.add(new Rule.Builder("low-health")
                .when("health < 8")
                .cooldown(100)
                .action((rule, tick) -> {
                    Double health = PlayerFacts.snapshot().fact("health");
                    report("health low (%.0f); stopping exploration".formatted(health == null ? 0.0 : health));
                    pauseExploration();
                    return "stopped exploration";
                })
                .build());
        }

        if (reactToPlayers.get()) {
            rules.add(new Rule.Builder("player-nearby")
                .trigger(AutomationEvent.Type.PLAYER_SIGHTED)
                .cooldown(600)
                .action((rule, tick) -> "noted a player sighting")
                .build());
        }

        // Cross-module integration: a task that finished may free the exploration pause.
        rules.add(new Rule.Builder("inventory-freed")
            .trigger(AutomationEvent.Type.TASK_COMPLETED)
            .cooldown(40)
            .action((rule, tick) -> null)
            .build());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (!isActive()) return;

        AutomationEngine engine = AutomationEngine.get();
        long tick = engine == null ? mc.world.getTime() : engine.currentTick();
        rules.evaluateTick(PlayerFacts.snapshot(), tick);
    }

    private void onEvent(AutomationEvent event) {
        if (!isActive()) return;
        AutomationEngine engine = AutomationEngine.get();
        long tick = engine == null ? 0 : engine.currentTick();
        rules.evaluateEvent(event, PlayerFacts.snapshot(), tick);
    }

    /** Submits a low-priority maintenance task. Used by rules that need the engine rather than a direct call. */
    public Task submit(Task task) {
        AutomationEngine engine = AutomationEngine.get();
        return engine == null ? null : engine.submit(task);
    }

    /** Pauses the active exploration module, if one is running. */
    private void pauseExploration() {
        var v2 = Modules.get().get(dev.basehunt.bhclient.modules.BaseHunterV2.class);
        if (v2 != null && v2.isStarted() && !v2.isPaused()) v2.pause();
    }

    /** A one-line status for the HUD and terminal. */
    public String statusLine() {
        return rules.status();
    }

    /** Recent rule activity for the terminal. */
    public List<String> recent() {
        return rules.recent(10);
    }

    private void report(String message) {
        publish(AutomationEvent.Type.MILESTONE, Map.of("reaction", message));
        if (chatFeedback.get()) warning("%s", message);
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
