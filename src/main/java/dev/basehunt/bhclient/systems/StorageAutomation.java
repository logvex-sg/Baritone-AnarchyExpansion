package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ItemFilter;
import dev.basehunt.bhclient.core.logic.ProtectionGuard;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.QuantityPlanner;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.core.task.TaskRunner;
import dev.basehunt.bhclient.core.task.TaskState;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The runner that turns a storage task into real client actions: navigate, open, inspect, transfer, verify.
 *
 * <p>This is the layer the old code was missing. A task is not "click the open chest"; it is "get to the chest,
 * confirm it is loaded and reachable, open it through ordinary interaction, wait for the server to send its
 * contents, move exactly the requested amount, then confirm the move landed". Every one of those steps can
 * fail, and a failure is reported with a reason rather than being swallowed.
 *
 * <p>It works for both directions. A withdrawal reads the container as the source and the player inventory as
 * the destination; a deposit does the reverse. The mechanics are shared, and the only difference is which side
 * is filtered and which side is protected.
 *
 * <p>The runner never clicks a slot itself: it builds a plan and hands it to the
 * {@link ContainerInteractionManager}, which is the single clicker and the only thing that decides whether a
 * move succeeded. That is what keeps a task from duplicating the safety work, and it is why protection is
 * enforced in one place.
 */
public final class StorageAutomation implements TaskRunner {
    /** Which direction items move. */
    public enum Direction {
        WITHDRAW,
        DEPOSIT
    }

    /** The runner's own step, finer-grained than the task state so it can be resumed tick by tick. */
    private enum Step {
        NAVIGATE,
        OPEN,
        INSPECT,
        TRANSFER,
        VERIFY,
        DONE
    }

    private final Direction direction;

    private Step step = Step.NAVIGATE;
    private BlockPos targetPos;
    private int containerId = -1;
    private long stepStartTick;
    private int ticksInStep;
    private int openAttempts;
    private boolean transferSubmitted;
    private long transferSubmitTick;
    private int baselinePlayerCount;
    private int expectedMoved;
    private boolean allowPartial = true;
    private boolean continueAcross = false;
    private int settledRetries;

    public StorageAutomation(Direction direction) {
        this.direction = direction;
    }

    @Override
    public void start(Task task) {
        step = Step.NAVIGATE;
        ticksInStep = 0;
        openAttempts = 0;
        transferSubmitted = false;
        settledRetries = 0;
        expectedMoved = 0;

        Map<String, String> meta = task.metadata();
        containerId = parseInt(meta.get("containerId"), -1);
        allowPartial = !"false".equalsIgnoreCase(meta.getOrDefault("allowPartial", "true"));
        continueAcross = "true".equalsIgnoreCase(meta.getOrDefault("continueAcross", "false"));

        StorageRegistry registry = StorageRegistry.get();
        StorageRegistry.Container container = containerId >= 0 && registry != null ? registry.byId(containerId) : null;
        targetPos = container == null ? null : container.pos;

        task.step("preparing");
    }

    @Override
    public void tick(Task task) {
        ticksInStep++;

        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null || mc.world == null) {
            task.fail("no world");
            return;
        }

        switch (step) {
            case NAVIGATE -> tickNavigate(task);
            case OPEN -> tickOpen(task);
            case INSPECT -> tickInspect(task);
            case TRANSFER -> tickTransfer(task);
            case VERIFY -> tickVerify(task);
            case DONE -> { /* handled by the engine once the task is terminal */ }
        }
    }

    @Override
    public void stop(Task task) {
        NavigationManager navigation = NavigationManager.get();
        if (navigation != null) navigation.cancelOwner("storage-task");
        ContainerInteractionManager interaction = ContainerInteractionManager.get();
        if (interaction != null) interaction.cancelOwner("storage-task");
    }

    // Steps

    private void tickNavigate(Task task) {
        // A container is only worth travelling to when it exists and is on this server/dimension.
        if (targetPos == null) {
            // No container chosen: the caller is operating on whatever screen is open.
            if (!(ServerUtils.mc().currentScreen instanceof HandledScreen<?>)) {
                task.fail("no container selected and none is open");
                return;
            }
            enter(task, Step.INSPECT, "inspecting the open container");
            return;
        }

        if (ContainerHelper.isReachable(targetPos)) {
            enter(task, Step.OPEN, "at the container");
            return;
        }

        if (!ContainerHelper.isChunkLoaded(targetPos)) {
            task.step("waiting for the chunk to load");
        } else {
            task.step("navigating to the container");
        }

        NavigationManager navigation = NavigationManager.get();
        if (navigation == null) {
            task.fail("navigation unavailable");
            return;
        }

        NavigationManager.Request request = navigation.active();
        if (request == null || !request.destination().equals(targetPos) || !request.isActive()) {
            request = navigation.start("storage-task", targetPos, ContainerHelper.REACH);
            publish(AutomationEvent.Type.NAVIGATION_STARTED, task,
                Map.of("destination", targetPos.toShortString()));
        }

        navigation.tick();

        if (request.status() == NavigationManager.Status.ARRIVED) {
            publish(AutomationEvent.Type.NAVIGATION_ARRIVED, task,
                Map.of("destination", targetPos.toShortString()));
            enter(task, Step.OPEN, "arrived");
            return;
        }

        if (request.status() == NavigationManager.Status.UNREACHABLE
            || request.status() == NavigationManager.Status.FAILED) {
            publish(AutomationEvent.Type.NAVIGATION_FAILED, task,
                Map.of("destination", targetPos.toShortString(), "reason", request.failure()));
            task.fail("could not reach the container: " + request.failure());
            return;
        }

        // A hard ceiling so a destination that is technically reachable but never actually reached cannot spin.
        if (ticksInStep > 20 * 60 * 3) {
            task.fail("navigation timed out");
        }
    }

    private void tickOpen(Task task) {
        var mc = ServerUtils.mc();

        if (mc.currentScreen instanceof HandledScreen<?> screen) {
            ScreenHandler handler = screen.getScreenHandler();
            if (!StorageOperations.containerSlotIds(handler).isEmpty()) {
                enter(task, Step.INSPECT, "container open");
                return;
            }
        }

        if (targetPos == null) {
            task.fail("no container to open");
            return;
        }

        if (!ContainerHelper.isReachable(targetPos)) {
            // The container moved out of reach (we drifted, or it was a ghost). Go back to navigating.
            enter(task, Step.NAVIGATE, "moved out of reach");
            return;
        }

        if (openAttempts >= 5) {
            task.fail("the container would not open");
            return;
        }

        // Space the attempts out so we are not hammering the server with interactions.
        if (ticksInStep % 10 == 0) {
            String error = StorageOperations.openContainerBlock(targetPos);
            openAttempts++;
            if (error != null && openAttempts >= 5) {
                task.fail("could not open the container: " + error);
            }
        }

        if (ticksInStep > 100) {
            task.fail("the container did not open in time");
        }
    }

    private void tickInspect(Task task) {
        var mc = ServerUtils.mc();
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            task.fail("the container closed before it could be read");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();

        // Record the contents so the registry stays honest about what is where.
        if (targetPos != null) {
            StorageRegistry registry = StorageRegistry.get();
            StorageRegistry.Container container = containerId >= 0 && registry != null
                ? registry.byId(containerId) : null;
            if (container != null) StorageOperations.observe(container, handler);
        }

        ItemFilter filter = filterFor(task);
        Set<String> protectedItems = protectedItems();
        int available = direction == Direction.WITHDRAW
            ? StorageOperations.countInContainer(handler, filter, protectedItems)
            : StorageOperations.countInPlayer(handler, filter, protectedItems);

        int requested = task.requested();

        if (available <= 0) {
            task.fail("no matching items were found in the " + (direction == Direction.WITHDRAW ? "container" : "inventory"));
            return;
        }

        // Count the player's baseline now so verification compares like with like.
        baselinePlayerCount = StorageOperations.countInPlayer(handler, filter, Set.of());

        if (direction == Direction.WITHDRAW) {
            List<QuantityPlanner.Destination> destinations = StorageOperations.playerDestinations(
                handler, null, guard().protectedIndices());
            if (!QuantityPlanner.hasRoom(destinations)) {
                task.fail("the inventory has no room for the withdrawal");
                return;
            }
        } else {
            List<QuantityPlanner.Destination> destinations = StorageOperations.containerDestinations(handler, null);
            if (!QuantityPlanner.hasRoom(destinations)) {
                task.fail("the container is full");
                return;
            }
        }

        int want = requested <= 0 ? Integer.MAX_VALUE : requested;
        int planned = Math.min(want == Integer.MAX_VALUE ? available : want, available);

        if (requested > 0 && available < requested && !allowPartial) {
            task.fail("only %d of the %d requested are available and partial moves are disabled"
                .formatted(available, requested));
            return;
        }

        if (requested <= 0) task.request(available);
        expectedMoved = planned;

        enter(task, Step.TRANSFER, "moving " + planned + " item(s)");
    }

    private void tickTransfer(Task task) {
        var mc = ServerUtils.mc();
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            task.fail("the container closed mid-transfer");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        ContainerInteractionManager interaction = ContainerInteractionManager.get();
        if (interaction == null) {
            task.fail("storage system unavailable");
            return;
        }

        if (!transferSubmitted) {
            if (interaction.isBusyWith("storage-task")) {
                // Another click sequence owned by us is still finishing; wait rather than queue a second.
                return;
            }

            List<SortPlanner.Move> moves = buildMoves(handler, task);
            if (moves.isEmpty()) {
                task.fail("nothing could be planned for the " + direction.name().toLowerCase());
                return;
            }

            List<String> refused = new ArrayList<>();
            List<SortPlanner.Move> allowed = StorageOperations.enforceProtection(handler, moves, guard(), refused);
            if (!refused.isEmpty()) {
                for (String line : refused) dev.basehunt.bhclient.BaseHuntingAddon.LOG.info("storage: {}", line);
            }
            if (allowed.isEmpty()) {
                task.fail("every planned move was refused by inventory protection");
                return;
            }

            boolean queued = interaction.submit(new ContainerInteractionManager.Operation(
                "storage-task", kind(), task.name(), allowed, true, timeoutTicks(allowed), 2));
            if (!queued) return;

            transferSubmitted = true;
            transferSubmitTick = AutomationManager.get() == null ? 0 : AutomationManager.get().currentTick();
            task.setState(TaskState.PROCESSING);
            return;
        }

        if (interaction.isBusyWith("storage-task")) return;

        // The click sequence is over; ask the interaction manager whether it actually landed.
        ContainerInteractionManager.Result result = interaction.lastResult();
        if (result == null || result.finishedAt() < transferSubmitTick) return;

        if (!result.success()) {
            task.fail("the transfer did not complete: " + result.message());
            return;
        }

        enter(task, Step.VERIFY, "verifying");
    }

    private void tickVerify(Task task) {
        var mc = ServerUtils.mc();
        ScreenHandler handler = mc.currentScreen instanceof HandledScreen<?> screen
            ? screen.getScreenHandler() : mc.player.currentScreenHandler;

        ItemFilter filter = filterFor(task);
        int now = direction == Direction.WITHDRAW
            ? StorageOperations.countInPlayer(handler, filter, Set.of())
            : StorageOperations.countInPlayer(handler, filter, Set.of());

        int moved = direction == Direction.WITHDRAW ? now - baselinePlayerCount : baselinePlayerCount - now;

        if (moved > 0) task.advance(Math.min(moved, task.requested() <= 0 ? moved : task.requested()));

        if (moved >= expectedMoved || (task.requested() > 0 && task.attained() >= task.requested())) {
            task.step("done");
            task.complete();
            publish(direction == Direction.WITHDRAW ? AutomationEvent.Type.ITEMS_WITHDRAWN
                : AutomationEvent.Type.ITEMS_DEPOSITED, task, Map.of("moved", String.valueOf(moved)));
            step = Step.DONE;
            return;
        }

        // The clicker reported success but the counts do not agree. Give the server a couple of ticks to catch
        // up before failing, so a slow sync is not treated as a phantom success.
        if (settledRetries++ > 10) {
            if (moved > 0) {
                task.advance(moved);
                task.step("partially moved");
                task.complete();
            } else {
                task.fail("the transfer was reported but the inventory did not change");
            }
            step = Step.DONE;
        }
    }

    // Planning

    private List<SortPlanner.Move> buildMoves(ScreenHandler handler, Task task) {
        ItemFilter filter = filterFor(task);
        Set<String> protectedItems = protectedItems();
        int amount = task.requested() <= 0 ? Integer.MAX_VALUE : task.requested();

        QuantityPlanner.Plan plan;
        if (direction == Direction.WITHDRAW) {
            List<QuantityPlanner.Source> sources = StorageOperations.containerSources(handler);
            List<QuantityPlanner.Destination> destinations = StorageOperations.playerDestinations(
                handler, null, guard().protectedIndices());
            plan = QuantityPlanner.plan(sources, destinations, filter, amount, allowPartial, protectedItems);
        } else {
            List<QuantityPlanner.Source> sources = StorageOperations.playerSources(handler);
            List<QuantityPlanner.Destination> destinations = StorageOperations.containerDestinations(handler, null);
            plan = QuantityPlanner.plan(sources, destinations, filter, amount, allowPartial, protectedItems);
        }

        return plan.moves();
    }

    private ContainerInteractionManager.Kind kind() {
        return direction == Direction.WITHDRAW
            ? ContainerInteractionManager.Kind.WITHDRAW
            : ContainerInteractionManager.Kind.DEPOSIT;
    }

    private ItemFilter filterFor(Task task) {
        String raw = task.meta("filter");
        if (raw == null || raw.isBlank() || raw.equals("*")) return ItemFilter.any();

        dev.basehunt.bhclient.core.logic.StorageCategory category = dev.basehunt.bhclient.utils.CategoryParser.one(raw);
        if (category != null) return ItemFilter.category(category);
        return ItemFilter.term(raw);
    }

    private ProtectionGuard guard() {
        var mc = ServerUtils.mc();
        int selected = mc == null || mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
        return ProtectionGuard.of(9, Set.of(), protectedItems(), true, selected);
    }

    private Set<String> protectedItems() {
        StorageRegistry registry = StorageRegistry.get();
        // The registry does not own protection; the default elytra/totem list is the floor. Modules that need a
        // richer list pass it through their own settings; here we keep the universal minimum.
        return ProtectionRules.parseList("elytra,totem_of_undying");
    }

    private long timeoutTicks(List<SortPlanner.Move> moves) {
        int clicks = 0;
        for (SortPlanner.Move move : moves) clicks += move.clickCost();
        return Math.max(200, clicks * 3L + 100);
    }

    private void enter(Task task, Step next, String label) {
        step = next;
        ticksInStep = 0;
        task.step(label);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Convenience for callers building a withdraw task. */
    public static Task withdrawTask(long id, String filter, int amount, int containerId, boolean allowPartial) {
        Task task = new Task(id, "withdraw " + filter, "Withdraw items from storage",
            Task.Category.WITHDRAW, dev.basehunt.bhclient.core.Priority.INVENTORY_MANAGEMENT);
        task.request(amount);
        task.meta("filter", filter);
        task.meta("containerId", String.valueOf(containerId));
        task.meta("allowPartial", String.valueOf(allowPartial));
        task.meta("direction", "withdraw");
        task.maxAttempts(2);
        return task;
    }

    /** Convenience for callers building a deposit task. */
    public static Task depositTask(long id, String filter, int amount, int containerId, boolean allowPartial) {
        Task task = new Task(id, "deposit " + filter, "Deposit items into storage",
            Task.Category.DEPOSIT, dev.basehunt.bhclient.core.Priority.INVENTORY_MANAGEMENT);
        task.request(amount);
        task.meta("filter", filter);
        task.meta("containerId", String.valueOf(containerId));
        task.meta("allowPartial", String.valueOf(allowPartial));
        task.meta("direction", "deposit");
        task.maxAttempts(2);
        return task;
    }

    private void publish(AutomationEvent.Type type, Task task, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager == null) return;
        manager.publish(AutomationEvent.of(type, task.name(), data));
    }
}
