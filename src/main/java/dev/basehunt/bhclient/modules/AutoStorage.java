package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.ItemFilter;
import dev.basehunt.bhclient.core.logic.QuantityPlanner;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.StorageAutomation;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.systems.StorageRegistry;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user-facing front of the storage automation: exact-quantity withdrawals and deposits, and restocking,
 * all run as {@link Task}s on the shared {@link AutomationEngine}.
 *
 * <p>Every operation is a task, not an inline click loop, so it is serialised against every other automation,
 * it survives being interrupted, and it reports a real outcome (completed, partial, failed) instead of a
 * fire-and-forget. That is the difference between "the chest is open, move things" and "go to container 4,
 * take exactly 64 ender pearls, and tell me if only 40 were there".
 *
 * <p>Protection is a setting here, but it is enforced in the operation layer, not trusted: the hotbar, the
 * worn armour and the offhand are always off limits, and an item on the protected list is refused before a
 * click is sent.
 */
public class AutoStorage extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgTravel = settings.createGroup("Travel");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print automation activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> defaultAmount = sgGeneral.add(new IntSetting.Builder()
        .name("default-amount")
        .description("How many items to move when a command gives no amount. 0 means all available.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 640)
        .build()
    );

    private final Setting<Boolean> allowPartial = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-partial")
        .description("Move what is available when the full amount is not present, instead of failing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> protectedItems = sgSafety.add(new StringListSetting.Builder()
        .name("protected-items")
        .description("Item ids that must never be moved by automation.")
        .defaultValue(List.of("elytra", "totem_of_undying", "shulker_box"))
        .build()
    );

    private final Setting<Boolean> travelToContainers = sgTravel.add(new BoolSetting.Builder()
        .name("travel-to-containers")
        .description("Walk to a registered container when it is out of reach. Requires Baritone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preferNearest = sgTravel.add(new BoolSetting.Builder()
        .name("prefer-nearest")
        .description("Choose the nearest registered container holding the item, not the highest priority.")
        .defaultValue(true)
        .build()
    );

    public AutoStorage() {
        super(BaseHuntingAddon.CATEGORY, "auto-storage",
            "Runs exact-quantity withdrawals, deposits and restocks as queued automation tasks.");
    }

    // Public API used by the commands, the GUI and the chat router.

    /**
     * Withdraws {@code amount} of a matching item from a container into the player's inventory.
     *
     * <p>When no container is open, the nearest registered container that holds the item is chosen and the task
     * travels to it. When a container is open, the task uses that one.
     */
    public String withdraw(String filter, int amount) {
        return start(StorageAutomation.Direction.WITHDRAW, filter, amount);
    }

    /** Deposits {@code amount} of a matching item from the player's inventory into a container. */
    public String deposit(String filter, int amount) {
        return start(StorageAutomation.Direction.DEPOSIT, filter, amount);
    }

    private String start(StorageAutomation.Direction direction, String filter, int amount) {
        AutomationEngine engine = AutomationEngine.get();
        if (engine == null) return "the automation engine is not running";
        if (filter == null || filter.isBlank()) return "specify an item";

        int resolvedAmount = amount > 0 ? amount : defaultAmount.get();
        int containerId = chooseContainer(direction, filter);

        if (direction == StorageAutomation.Direction.WITHDRAW && containerId < 0
            && !(mc.currentScreen instanceof HandledScreen<?>)) {
            return "no container is open and none registered holds '" + filter + "'";
        }

        Task task = direction == StorageAutomation.Direction.WITHDRAW
            ? StorageAutomation.withdrawTask(0, filter, resolvedAmount, containerId, allowPartial.get())
            : StorageAutomation.depositTask(0, filter, resolvedAmount, containerId, allowPartial.get());

        Task submitted = engine.submit(task);
        if (submitted == null) return "a matching task is already queued";

        if (chatFeedback.get()) {
            info("%s %s%s", direction == StorageAutomation.Direction.WITHDRAW ? "Withdrawing" : "Depositing",
                filter, resolvedAmount > 0 ? " x" + resolvedAmount : " (all)");
        }
        publish(AutomationEvent.Type.TASK_QUEUED, Map.of("task", task.name(), "container",
            String.valueOf(containerId)));
        return submitted.describe();
    }

    /**
     * Chooses which container to use: the open one when there is one, otherwise the nearest (or
     * highest-priority) registered container that actually holds the item, when travel is enabled.
     */
    private int chooseContainer(StorageAutomation.Direction direction, String filter) {
        if (mc.currentScreen instanceof HandledScreen<?> screen) {
            // The open container has no registry id; the task will operate on the live screen.
            return -1;
        }

        if (direction == StorageAutomation.Direction.DEPOSIT || !travelToContainers.get()) return -1;

        StorageRegistry registry = StorageRegistry.get();
        if (registry == null || mc.player == null) return -1;

        ItemFilter itemFilter = ItemFilter.term(filter);
        StorageRegistry.Container best = null;
        double bestDistance = Double.MAX_VALUE;

        for (StorageRegistry.Container container : registry.forCurrentServer()) {
            boolean holds = false;
            for (StorageRegistry.Observed observed : container.contents()) {
                if (itemFilter.matches(observed.itemId(), observed.name(), null)) {
                    holds = true;
                    break;
                }
            }
            if (!holds) continue;

            if (preferNearest.get()) {
                double distance = container.pos.getSquaredDistance(mc.player.getBlockPos());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = container;
                }
            } else if (best == null || container.priority < best.priority) {
                best = container;
            }
        }

        return best == null ? -1 : best.id;
    }

    /** A preview of what a withdrawal would move, without doing it. */
    public List<String> previewWithdraw(String filter, int amount) {
        HandledScreen<?> screen = mc.currentScreen instanceof HandledScreen<?> handled ? handled : null;
        if (screen == null) return List.of("open a container to preview a withdrawal");

        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));
        ItemFilter itemFilter = ItemFilter.term(filter);
        int available = StorageOperations.countInContainer(screen.getScreenHandler(), itemFilter, protectedSet);
        int want = amount > 0 ? Math.min(amount, available) : available;

        return List.of("available: %d, will move: %d%s".formatted(available, want,
            available < (amount > 0 ? amount : available) ? " (partial)" : ""));
    }

    /** True when the protected list refuses this item. */
    public boolean isProtected(String itemId) {
        return ProtectionRules.isProtectedItem(itemId, ProtectionRules.parseList(String.join(",",
            protectedItems.get())));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // The module itself does nothing on tick; the engine drives the tasks. The tick handler exists only so
        // the module is a normal participant and can be shown as active in the HUD.
    }

    /** A one-line status for the HUD. */
    public String statusLine() {
        AutomationEngine engine = AutomationEngine.get();
        return engine == null ? "engine unavailable" : engine.statusLine();
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
