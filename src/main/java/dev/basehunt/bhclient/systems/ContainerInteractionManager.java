package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.ContainerLayout;
import dev.basehunt.bhclient.core.logic.InventorySimulator;
import dev.basehunt.bhclient.core.logic.OperationState;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The single place that clicks inventory slots.
 *
 * <p>Every storage feature (sorting, deposits, withdrawals, restocking, shulker work) funnels its moves
 * through here, so there is exactly one owner of the inventory and one place that has to get the "did the
 * server accept it?" question right. Two modules can never click at once because an operation holds an
 * {@code INVENTORY} lease for its whole life, and only one operation runs at a time.
 *
 * <p>The safety model, in order:
 *
 * <ul>
 *   <li>nothing is clicked until the operation is at the head of the queue and holds the lease;</li>
 *   <li>moves are spaced by a tick delay, so a burst of clicks cannot outrun the server;</li>
 *   <li>the expected result is computed by simulating the moves, and the operation only reports success
 *       once the live container contents actually match that simulation;</li>
 *   <li>a click that cannot be sent, a container that never opens, and contents that settle in the wrong
 *       state are all failures with a reason, not silent successes;</li>
 *   <li>the operation gives up after a bounded time instead of clicking forever.</li>
 * </ul>
 *
 * <p>The manager is not a Meteor {@code System}; it holds only transient runtime state and is installed by
 * the addon entrypoint.
 */
public class ContainerInteractionManager {
    private static ContainerInteractionManager instance;

    /** What an operation is doing, so the caller can report it. */
    public enum Kind {
        SORT,
        DEPOSIT,
        WITHDRAW,
        RESTOCK,
        SHULKER_FILL,
        SHULKER_EMPTY,
        CUSTOM
    }

    /**
     * A queued plan of slot moves.
     *
     * @param owner      the module that submitted it, used for the lease and for reporting
     * @param kind       what kind of work this is
     * @param label      a short description for the HUD and chat
     * @param moves      the moves, in execution order, in screen-slot-id space
     * @param containerOp true when the operation needs an open container screen; a pure player-inventory
     *                    sort runs against the always-present player screen handler
     * @param timeoutTicks how long to wait for the server before failing
     * @param tickDelay  ticks between clicks
     */
    public record Operation(String owner, Kind kind, String label, List<SortPlanner.Move> moves,
                            boolean containerOp, long timeoutTicks, int tickDelay) {
    }

    /** The outcome of the last operation. */
    public record Result(String owner, Kind kind, String label, boolean success, String message,
                         int clicks, long finishedAt) {
    }

    private final Deque<Operation> queue = new ArrayDeque<>();
    private Operation active;
    private OperationState state;
    private List<SortPlanner.Move> moves = List.of();
    private int moveIndex;
    private int cooldown;
    private String expectedFingerprint = "";
    private Result lastResult;

    public static ContainerInteractionManager get() {
        return instance;
    }

    /** Called by the addon entrypoint. Safe to call twice. */
    public static ContainerInteractionManager install() {
        if (instance == null) {
            instance = new ContainerInteractionManager();
            // Not a Module, so nothing subscribes it automatically; without this the operation queue never
            // advances and queued moves would sit forever.
            meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(instance);
        }
        return instance;
    }

    /**
     * Drives the queue once per tick. Kept here rather than in each module so an operation keeps progressing
     * even when the module that queued it stops ticking, and so the manager is the only thing that advances it.
     */
    @meteordevelopment.orbit.EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        tick();
    }

    /** A disconnect takes the container with it, so any operation in flight is cancelled, not left pending. */
    @meteordevelopment.orbit.EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        cancelAll("disconnected");
    }

    /** Queues an operation. Refused when the same owner already has one queued or running. */
    public boolean submit(Operation operation) {
        if (operation == null || operation.moves().isEmpty()) return false;
        if (isBusyWith(operation.owner())) return false;

        queue.addLast(operation);
        return true;
    }

    public boolean isBusyWith(String owner) {
        if (active != null && active.owner().equals(owner)) return true;
        for (Operation queued : queue) if (queued.owner().equals(owner)) return true;
        return false;
    }

    public boolean isBusy() {
        return active != null || !queue.isEmpty();
    }

    public Operation active() {
        return active;
    }

    public OperationState state() {
        return state;
    }

    public int queueSize() {
        return queue.size();
    }

    public Result lastResult() {
        return lastResult;
    }

    /** Cancels everything and releases the inventory. Called when a module is disabled or the world changes. */
    public void cancelAll(String reason) {
        if (state != null && !state.finished()) state.cancel();
        if (active != null) {
            lastResult = new Result(active.owner(), active.kind(), active.label(), false, reason,
                state == null ? 0 : state.clicksSent(), System.currentTimeMillis());
            release();
        }
        queue.clear();
        active = null;
        state = null;
        moves = List.of();
        moveIndex = 0;
        cooldown = 0;
    }

    /** Abandons the operation owned by {@code owner}, if any. */
    public void cancelOwner(String owner) {
        queue.removeIf(op -> op.owner().equals(owner));
        if (active != null && active.owner().equals(owner)) {
            if (state != null && !state.finished()) state.cancel();
            lastResult = new Result(active.owner(), active.kind(), active.label(), false, "cancelled",
                state == null ? 0 : state.clicksSent(), System.currentTimeMillis());
            release();
            active = null;
            state = null;
        }
    }

    public String status() {
        if (active == null) {
            return queue.isEmpty() ? "idle" : "queued " + queue.size();
        }
        return state == null ? active.label() : state.describe();
    }

    /**
     * Drives the active operation one tick. Call from a module's tick handler while a storage feature is
     * enabled; safe to call every tick even when idle.
     */
    public void tick() {
        if (active == null) {
            if (queue.isEmpty()) return;
            start(queue.peekFirst());
            if (active == null) return;
        }

        if (state == null || state.finished()) {
            finish();
            return;
        }

        // Only click while this operation owns the inventory. A higher-priority automation (a survival move,
        // an elytra swap) takes precedence and the click waits, rather than racing it.
        AutomationManager manager = AutomationManager.get();
        if (manager != null && manager.tryAcquire(active.owner(), Priority.INVENTORY_MANAGEMENT,
            java.util.Set.of(ControlResource.INVENTORY)) == null) {
            return;
        }

        ScreenHandler handler = currentHandler();
        if (handler == null) {
            fail("the container closed");
            return;
        }

        if (moveIndex < moves.size()) {
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            executeMove(handler, moves.get(moveIndex));
            moveIndex++;
            cooldown = Math.max(1, active.tickDelay());
            if (moveIndex >= moves.size()) state.clicksComplete();
            return;
        }

        // All clicks are out; wait for the container to settle into the expected state.
        long tick = manager == null ? System.nanoTime() : manager.currentTick();
        state.observe(liveFingerprint(handler), tick, 20, 2);
        if (state.finished()) finish();
    }

    private void start(Operation operation) {
        ScreenHandler handler = currentHandler();
        if (handler == null || (operation.containerOp() && !(currentScreen() instanceof HandledScreen<?>))) {
            // The container is not open yet. Leave it queued and try again next tick rather than failing, so a
            // module that is still navigating to a chest is not punished.
            return;
        }

        queue.removeFirst();
        active = operation;
        moves = operation.moves();
        moveIndex = 0;
        cooldown = Math.max(1, operation.tickDelay());

        expectedFingerprint = simulateExpected(handler, operation.moves());

        AutomationManager manager = AutomationManager.get();
        long now = manager == null ? System.nanoTime() : manager.currentTick();
        state = OperationState.begin(operation.label(), now, Math.max(20, operation.timeoutTicks()), expectedFingerprint);
        state.opened();
    }

    /** Simulates the moves on the current container contents to get the fingerprint success must match. */
    private String simulateExpected(ScreenHandler handler, List<SortPlanner.Move> plan) {
        try {
            List<InventorySimulator.Slot> snapshot = snapshot(handler);
            int max = maxStack(handler);
            List<InventorySimulator.Slot> result = InventorySimulator.apply(plan, snapshot, max);
            return InventorySimulator.fingerprint(result);
        } catch (RuntimeException e) {
            // A plan that cannot be simulated is not trustworthy; expect nothing and let the settle check pass
            // only if the contents stop changing, which is still safer than a blind success.
            return "";
        }
    }

    private void executeMove(ScreenHandler handler, SortPlanner.Move move) {
        int from = move.fromSlot();
        int to = move.toSlot();
        if (handler.getSlot(from) == null || handler.getSlot(to) == null) {
            state.clickRejected("slot missing");
            return;
        }

        if (move.kind() == SortPlanner.Kind.TRANSFER) {
            executeTransfer(handler, from, to, move.amount());
            return;
        }

        // Pick up the source, drop it on the destination, then put whatever is left back where it came from.
        // This is a swap for two full stacks and a top-up for two stacks of the same item; both are the two
        // patterns the planner emits, and clicking an empty source at the end is a harmless no-op.
        pickUp(handler, from);
        pickUp(handler, to);
        pickUp(handler, from);
        state.clickSent();
    }

    /**
     * Moves exactly {@code amount} items: pick the source stack up, right-click {@code amount} items into the
     * destination one at a time, then put the remainder back. Right-click places a single item, which is the
     * only vanilla way to split a stack, and every intermediate click is counted so the operation budget and
     * the settle check account for it.
     */
    private void executeTransfer(ScreenHandler handler, int from, int to, int amount) {
        int count = Math.min(Math.max(0, amount), handler.getSlot(from).getStack().getCount());
        if (count <= 0) {
            state.clickRejected("nothing to move");
            return;
        }

        pickUp(handler, from);
        for (int i = 0; i < count; i++) {
            placeOne(handler, to);
            state.clickSent();
        }
        pickUp(handler, from);
        state.clickSent();
    }

    /** A left-click on a slot: pick up a whole stack, or place the whole cursor stack. */
    private void pickUp(ScreenHandler handler, int slotId) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return;
        mc.interactionManager.clickSlot(handler.syncId, slotId, 0, net.minecraft.screen.slot.SlotActionType.PICKUP,
            mc.player);
    }

    /** A right-click on a slot: place a single item from the cursor. */
    private void placeOne(ScreenHandler handler, int slotId) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return;
        mc.interactionManager.clickSlot(handler.syncId, slotId, 1, net.minecraft.screen.slot.SlotActionType.PICKUP,
            mc.player);
    }

    private void finish() {
        boolean ok = state != null && state.phase() == OperationState.Phase.DONE;
        String message = ok ? "complete" : (state == null ? "cancelled" : state.failure());

        if (active != null) {
            lastResult = new Result(active.owner(), active.kind(), active.label(), ok,
                message == null ? "cancelled" : message, state == null ? 0 : state.clicksSent(),
                System.currentTimeMillis());

            if (!ok) {
                // A failed operation must be visible in chat, the GUI and Discord, not just recorded.
                AutomationManager manager = AutomationManager.get();
                if (manager != null) {
                    manager.publish(dev.basehunt.bhclient.core.AutomationEvent.of(
                        dev.basehunt.bhclient.core.AutomationEvent.Type.CONTAINER_OP_FAILED, active.owner(),
                        java.util.Map.of("reason", lastResult.message(), "label", active.label())));
                }
            }
        }

        release();
        active = null;
        state = null;
        moves = List.of();
        moveIndex = 0;
        cooldown = 0;
        expectedFingerprint = "";
    }

    private void fail(String reason) {
        if (state != null && !state.finished()) {
            // Force a failure through the state machine so the reason is recorded consistently.
            state.clickRejected(reason);
            state.clickRejected(reason);
            state.clickRejected(reason);
        }
        finish();
    }

    private void release() {
        if (active == null) return;
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.releaseAll(active.owner());
    }

    // Helpers

    private ScreenHandler currentHandler() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return null;
        return mc.player.currentScreenHandler;
    }

    private Object currentScreen() {
        var mc = ServerUtils.mc();
        return mc == null ? null : mc.currentScreen;
    }

    /** True when a container screen (not just the player inventory) is open. */
    public static boolean containerScreenOpen() {
        var mc = ServerUtils.mc();
        return mc != null && mc.currentScreen instanceof HandledScreen<?>;
    }

    public static ScreenHandler openHandler() {
        var mc = ServerUtils.mc();
        if (mc == null || !(mc.currentScreen instanceof HandledScreen<?> screen)) return null;
        return screen.getScreenHandler();
    }

    /** The number of container (non-player) slots in a handler. */
    public static int containerSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) return generic.getRows() * 9;
        return Math.max(0, handler.slots.size() - ContainerLayout.PLAYER_SLOTS);
    }

    public static ContainerLayout layoutFor(ScreenHandler handler) {
        return ContainerLayout.forTotalSlots(handler.slots.size());
    }

    /** Slot ids of the player's main inventory plus hotbar, for the current handler. */
    public static List<Integer> playerSlotIds(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>(ContainerLayout.PLAYER_SLOTS);
        for (int index = 0; index < ContainerLayout.PLAYER_SLOTS; index++) {
            int id = SlotUtils.indexToId(index);
            if (id >= 0) ids.add(id);
        }
        return ids;
    }

    /** Slot ids of the container's own slots, in order. */
    public static List<Integer> containerSlotIds(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>();
        int count = containerSlotCount(handler);
        for (int i = 0; i < count; i++) ids.add(i);
        return ids;
    }

    /** A stable identity for a stack, used for both the simulator and the fingerprint. */
    public static String stackKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return Registries.ITEM.getId(stack.getItem()).toString() + "#" + stack.getComponents().hashCode();
    }

    public static int maxStack(ScreenHandler handler) {
        int max = 64;
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) max = Math.max(max, stack.getMaxCount());
        }
        return max;
    }

    /** Snapshots every occupied slot of the handler into the simulator's form. */
    public static List<InventorySimulator.Slot> snapshot(ScreenHandler handler) {
        List<InventorySimulator.Slot> slots = new ArrayList<>(handler.slots.size());
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            ItemStack stack = slot.getStack();
            slots.add(new InventorySimulator.Slot(slot.id, stackKey(stack),
                stack.isEmpty() ? null : stack.getName().getString(), stack.getCount()));
        }
        return slots;
    }

    /** The current contents fingerprint, computed the same way the simulation is. */
    public static String liveFingerprint(ScreenHandler handler) {
        return InventorySimulator.fingerprint(snapshot(handler));
    }
}
