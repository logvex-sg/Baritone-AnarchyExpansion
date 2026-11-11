package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.WorkflowEngine;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.systems.WorkflowManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.Map;

/**
 * Runs the storage workflows and reports the state of the whole storage system.
 *
 * <p>A workflow is a named sequence of storage steps ("sort, deposit, restock, report") and this module is
 * the thing that actually drives one: it asks each step's module to do its part, waits for the underlying
 * container operation to finish, and only then moves to the next step. That sequencing is what stops a
 * workflow from, say, restocking before the deposit that freed the space.
 *
 * <p>It is also the addon's storage status view: the HUD and the {@code /bh storage status} command read
 * their numbers from here, so there is one place that knows how many containers are registered, what the
 * interaction manager is doing, and whether the last operation failed.
 */
public class StorageMonitor extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWorkflow = settings.createGroup("Workflow");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print workflow progress in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireContainerOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("require-container-open")
        .description("Only run container steps while a container screen is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> workflow = sgWorkflow.add(new ProvidedStringSetting.Builder()
        .name("workflow")
        .description("Which workflow to run.")
        .defaultValue("full cleanup")
        .supplier(() -> {
            WorkflowManager manager = WorkflowManager.get();
            return manager == null ? new String[0]
                : manager.all().stream().map(WorkflowEngine.Workflow::name).toArray(String[]::new);
        })
        .build()
    );

    private final Setting<Boolean> runOnActivate = sgWorkflow.add(new BoolSetting.Builder()
        .name("run-on-activate")
        .description("Start the workflow as soon as the module is enabled.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> stepDelay = sgWorkflow.add(new IntSetting.Builder()
        .name("step-delay")
        .description("Ticks to wait between steps, so the server can settle.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );

    private final WorkflowEngine engine = new WorkflowEngine();
    private int stepCooldown;
    private boolean stepDispatched;
    private long dispatchTime;

    public StorageMonitor() {
        super(BaseHuntingAddon.CATEGORY, "storage-monitor",
            "Runs storage workflows and reports the state of the storage system.");
    }

    @Override
    public void onActivate() {
        engine.reset();
        stepCooldown = 0;
        stepDispatched = false;

        if (runOnActivate.get()) start(workflow.get());
    }

    @Override
    public void onDeactivate() {
        engine.stop();
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager != null) manager.cancelOwner(name);
    }

    /** Starts a workflow by name. Returns a short result message. */
    public String start(String name) {
        WorkflowManager manager = WorkflowManager.get();
        WorkflowEngine.Workflow definition = manager == null ? null : manager.find(name);
        if (definition == null) {
            for (WorkflowEngine.Workflow builtin : WorkflowEngine.builtins()) {
                if (builtin.name().equalsIgnoreCase(name)) { definition = builtin; break; }
            }
        }
        if (definition == null) return "no workflow named '" + name + "'";

        engine.put(definition);
        if (!engine.start(definition.name())) return "the workflow has no steps";

        if (chatFeedback.get()) info("Starting workflow '%s'.", definition.name());
        publish(AutomationEvent.Type.WORKFLOW_STARTED, Map.of("workflow", definition.name()));
        return "running " + definition.name();
    }

    public void stop() {
        engine.stop();
        if (chatFeedback.get()) info("Workflow stopped.");
    }

    public WorkflowEngine engine() {
        return engine;
    }

    /** The workflow name currently selected in the module's settings. */
    public String workflowName() {
        return workflow.get();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (stepCooldown > 0) {
            stepCooldown--;
            return;
        }

        if (!engine.isRunning()) return;

        WorkflowEngine.Step step = engine.currentStep();
        if (step == null) return;

        if (step.kind() == WorkflowEngine.Kind.WAIT) {
            engine.tickWait();
            return;
        }

        if (stepDispatched) {
            // A restock step runs its own multi-tick process (navigate, open, pull); wait for it to finish
            // rather than letting the workflow race ahead to the next step.
            if (step.kind() == WorkflowEngine.Kind.RESTOCK) {
                RestockManager restock = Modules.get().get(RestockManager.class);
                if (restock != null && restock.isRunning()) return;
            }

            ContainerInteractionManager interaction = ContainerInteractionManager.get();
            if (interaction != null && interaction.isBusy()) return;

            // The operation finished (or never queued). Trust only a result newer than this step's dispatch.
            boolean success = true;
            if (interaction != null) {
                ContainerInteractionManager.Result result = interaction.lastResult();
                if (result != null && result.finishedAt() >= dispatchTime) success = result.success();
            }

            stepDispatched = false;
            engine.completeStep(success);

            if (engine.status() == WorkflowEngine.Status.FAILED) {
                if (chatFeedback.get()) error("Workflow failed: %s", engine.failure());
                publish(AutomationEvent.Type.WORKFLOW_FAILED, Map.of(
                    "workflow", engine.activeWorkflow().name(), "step", step.describe()));
            } else {
                publish(AutomationEvent.Type.WORKFLOW_STEP_DONE, Map.of(
                    "workflow", engine.activeWorkflow().name(), "step", step.describe()));
                if (engine.status() == WorkflowEngine.Status.DONE) {
                    if (chatFeedback.get()) info("Workflow '%s' complete.", engine.activeWorkflow().name());
                    publish(AutomationEvent.Type.WORKFLOW_COMPLETED, Map.of(
                        "workflow", engine.activeWorkflow().name()));
                }
            }

            stepCooldown = stepDelay.get();
            return;
        }

        // Dispatch the step to the module that owns it.
        if (!dispatch(step)) return;
        stepDispatched = true;
        dispatchTime = java.lang.System.currentTimeMillis();
        stepCooldown = Math.max(2, stepDelay.get());
    }

    private boolean dispatch(WorkflowEngine.Step step) {
        InventoryManager inventory = Modules.get().get(InventoryManager.class);
        ChestSorter chest = Modules.get().get(ChestSorter.class);
        ShulkerManager shulker = Modules.get().get(ShulkerManager.class);

        boolean containerOpen = mc.currentScreen instanceof HandledScreen<?> screen;
        var handler = containerOpen ? ((HandledScreen<?>) mc.currentScreen).getScreenHandler() : mc.player.currentScreenHandler;

        return switch (step.kind()) {
            case SORT_INVENTORY -> {
                if (inventory == null) yield false;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                inventory.sortNow("workflow");
                yield true;
            }
            case ORGANIZE_HOTBAR -> {
                if (inventory == null) yield false;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                inventory.sortNow("workflow hotbar");
                yield true;
            }
            case DEPOSIT -> {
                if (chest == null || !containerOpen) {
                    if (requireContainerOpen.get() && chatFeedback.get()) warning("Deposit needs a container open.");
                    yield true;
                }
                if (chatFeedback.get()) info("Step: %s", step.describe());
                chest.deposit(handler);
                yield true;
            }
            case WITHDRAW -> {
                if (chest == null || !containerOpen) {
                    if (requireContainerOpen.get() && chatFeedback.get()) warning("Withdraw needs a container open.");
                    yield true;
                }
                if (chatFeedback.get()) info("Step: %s", step.describe());
                chest.withdraw(handler);
                yield true;
            }
            case SORT_CONTAINER -> {
                if (chest == null || !containerOpen) yield true;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                chest.sortContainer(handler);
                yield true;
            }
            case FILL_SHULKER -> {
                if (shulker == null || !(handler instanceof net.minecraft.screen.ShulkerBoxScreenHandler shulkerHandler)) yield true;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                shulker.fill(shulkerHandler);
                yield true;
            }
            case EMPTY_SHULKER -> {
                if (shulker == null || !(handler instanceof net.minecraft.screen.ShulkerBoxScreenHandler shulkerHandler)) yield true;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                shulker.empty(shulkerHandler);
                yield true;
            }
            case SORT_SHULKER -> {
                if (shulker == null || !(handler instanceof net.minecraft.screen.ShulkerBoxScreenHandler shulkerHandler)) yield true;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                shulker.sortShulker(shulkerHandler);
                yield true;
            }
            case RESTOCK -> {
                RestockManager restock = Modules.get().get(RestockManager.class);
                if (restock == null) yield false;
                if (chatFeedback.get()) info("Step: %s", step.describe());
                restock.start(step.payload());
                yield true;
            }
            case REPORT -> {
                if (chatFeedback.get()) info("%s", report());
                yield true;
            }
            case CLOSE_CONTAINER -> {
                mc.player.closeHandledScreen();
                yield true;
            }
            case WAIT -> true;
        };
    }

    /** A multi-line report of the storage system, for the command and the workflow report step. */
    public String report() {
        StringBuilder builder = new StringBuilder();

        StorageRegistry registry = StorageRegistry.get();
        int containers = registry == null ? 0 : registry.forCurrentServer().size();
        int verified = 0;
        if (registry != null) {
            for (StorageRegistry.Container container : registry.forCurrentServer()) if (container.verified) verified++;
        }

        ContainerInteractionManager interaction = ContainerInteractionManager.get();

        builder.append("Storage: ").append(containers).append(" container(s), ")
            .append(verified).append(" observed. ");
        builder.append("Interaction: ").append(interaction == null ? "unavailable" : interaction.status()).append(". ");
        builder.append("Workflow: ").append(engine.describe()).append(".");

        ContainerInteractionManager.Result last = interaction == null ? null : interaction.lastResult();
        if (last != null && !last.success()) {
            builder.append(" Last operation failed: ").append(last.message()).append('.');
        }

        return builder.toString();
    }

    /** One-line status for the HUD. */
    public String statusLine() {
        StorageRegistry registry = StorageRegistry.get();
        int containers = registry == null ? 0 : registry.forCurrentServer().size();
        ContainerInteractionManager interaction = ContainerInteractionManager.get();

        String interactionText = interaction == null ? "-" : interaction.status();
        return "%d container(s) | %s | %s".formatted(containers, interactionText, engine.describe());
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
