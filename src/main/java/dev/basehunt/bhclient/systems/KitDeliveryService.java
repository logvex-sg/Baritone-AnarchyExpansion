package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.sixbt.KitDeliveryMachine;
import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.core.sixbt.KitLoadoutRegistry;
import dev.basehunt.bhclient.core.sixbt.KitRequestQueue;
import dev.basehunt.bhclient.core.sixbt.KitRestockPlan;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The runtime half of a 6b6t kit delivery bot.
 *
 * <p>All the decision logic lives in the pure {@link KitDeliveryMachine} and {@link KitRequestQueue}; this
 * class only does the parts that need a live client - reading the world for the requester, sending the chat
 * lines, and dropping the items. Keeping the split this way is what lets the tricky state transitions be
 * tested without a Minecraft instance while this class stays a thin, obviously-correct glue layer.
 *
 * <p>It never invents packets: {@code /tpa}, {@code /tpcancel} and {@code /kill} go through the normal chat
 * path, exactly as if the player typed them, and items are dropped with the vanilla throw action. A server
 * that refuses any of it gets a report, not a retry loop.
 */
public final class KitDeliveryService {

    /** Sends a chat line. Injected so the service does not reach for the network handler itself. */
    @FunctionalInterface
    public interface ChatSender {
        void send(String line);
    }

    /** A status callback, so the module can print to chat and publish events. */
    public interface Listener {
        void onRequest(String requester, String kit);

        void onStarted(String requester, String kit);

        void onFinished(String requester, String kit);

        void onFailed(String requester, String reason);

        void onRestockNeeded(String kit, int missingItems);
    }

    private final KitDeliveryMachine machine = new KitDeliveryMachine();
    private final KitRequestQueue queue;
    private final Listener listener;

    private int arrivalRadius = 6;
    private int requesterGoneTicks = 100;
    private int tpaTimeoutTicks = 600;
    private int missingTicks;

    public KitDeliveryService(int capacity, long cooldownMillis, Listener listener) {
        this.queue = new KitRequestQueue(capacity, cooldownMillis);
        this.listener = listener;
    }

    public void setArrivalRadius(int blocks) {
        this.arrivalRadius = Math.max(2, blocks);
    }

    public void setRequesterGoneTicks(int ticks) {
        this.requesterGoneTicks = Math.max(20, ticks);
    }

    /** How long to wait for a requester to accept the teleport before giving up. */
    public void setTpaTimeoutTicks(int ticks) {
        this.tpaTimeoutTicks = Math.max(100, ticks);
    }

    /** Accepts a request into the queue. Returns a refusal reason, or null when accepted. */
    public String offer(String requester, String kit, long nowMillis) {
        String reason = queue.offer(new KitRequestQueue.Pending(requester, kit, nowMillis), nowMillis);
        if (reason == null && listener != null) listener.onRequest(requester, kit);
        return reason;
    }

    public boolean busy() {
        return machine.busy();
    }

    public String statusLine() {
        return machine.busy() ? machine.statusLine() : queue.statusLine();
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("delivery: " + machine.statusLine());
        lines.add("queue: " + queue.statusLine());
        return lines;
    }

    public void cancel(String reason) {
        machine.cancel(reason);
        machine.reset();
    }

    public void clear() {
        queue.clear();
        machine.reset();
    }

    /**
     * Advances the bot one tick: starts a queued delivery when idle, then drives the active one.
     *
     * @param mc       the client
     * @param sender   how to send chat
     * @param loadouts the kit loadouts available
     * @param requireStock whether a delivery is refused when the bot is not carrying the loadout
     */
    public void tick(MinecraftClient mc, ChatSender sender, KitLoadoutRegistry loadouts, boolean requireStock) {
        if (mc == null || mc.player == null || mc.world == null) return;

        if (!machine.busy()) {
            KitRequestQueue.Pending next = queue.peek();
            if (next == null) return;

            KitLoadout loadout = loadouts == null ? null : loadouts.find(next.kit());
            if (requireStock && loadout != null && !isStocked(mc, loadout)) {
                int missing = KitRestockPlan.compare(loadout, heldCounts(mc, loadout)).missingItems();
                queue.poll();
                queue.served(next.requester(), System.currentTimeMillis());
                if (listener != null) listener.onRestockNeeded(next.kit(), missing);
                return;
            }

            queue.poll();
            List<KitDeliveryMachine.Action> actions = machine.start(next.requester(), next.kit());
            if (listener != null) listener.onStarted(next.requester(), next.kit());
            run(mc, sender, loadouts, actions);
            return;
        }

        // Observe the world, then let the machine decide.
        boolean waiting = machine.state() == KitDeliveryMachine.State.WAITING_ARRIVAL;
        boolean arrived = isRequesterNear(mc, machine.requester());
        boolean gone = isRequesterGone(mc, machine.requester());
        boolean timedOut = waiting && !arrived && machine.ticksInState() >= tpaTimeoutTicks;
        int remaining = remainingLoadoutItems(mc, loadouts, machine.kitName());

        KitDeliveryMachine.Observation observation = new KitDeliveryMachine.Observation(
            arrived, false, timedOut, remaining, gone);
        List<KitDeliveryMachine.Action> actions = machine.tick(observation);
        run(mc, sender, loadouts, actions);
    }

    private void run(MinecraftClient mc, ChatSender sender, KitLoadoutRegistry loadouts,
                     List<KitDeliveryMachine.Action> actions) {
        for (KitDeliveryMachine.Action action : actions) {
            if (action instanceof KitDeliveryMachine.Action.SendTpa tpa) {
                if (sender != null) sender.send("/tpa " + tpa.player());
            } else if (action instanceof KitDeliveryMachine.Action.CancelTpa) {
                if (sender != null) sender.send("/tpcancel");
            } else if (action instanceof KitDeliveryMachine.Action.DropItem) {
                dropOneLoadoutItem(mc, loadouts, machine.kitName());
            } else if (action instanceof KitDeliveryMachine.Action.KillSelf) {
                if (sender != null) sender.send("/kill");
                if (listener != null) listener.onFinished(machine.requester(), machine.kitName());
                queue.served(machine.requester(), System.currentTimeMillis());
            } else if (action instanceof KitDeliveryMachine.Action.Report report) {
                if (listener != null && report.reason().startsWith("delivered")) {
                    // Already reported through onFinished.
                } else if (listener != null) {
                    listener.onFailed(machine.requester(), report.reason());
                }
            }
        }
    }

    // World reads

    private PlayerEntity findRequester(MinecraftClient mc, String name) {
        if (name == null || name.isBlank() || mc.world == null) return null;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    private boolean isRequesterNear(MinecraftClient mc, String name) {
        PlayerEntity requester = findRequester(mc, name);
        if (requester == null || mc.player == null) return false;
        double dx = requester.getX() - mc.player.getX();
        double dz = requester.getZ() - mc.player.getZ();
        double dy = requester.getY() - mc.player.getY();
        return dx * dx + dz * dz + dy * dy <= (double) arrivalRadius * arrivalRadius;
    }

    private boolean isRequesterGone(MinecraftClient mc, String name) {
        if (findRequester(mc, name) != null) {
            missingTicks = 0;
            return false;
        }
        // The requester may simply be out of render distance; only give up after a while.
        missingTicks++;
        return missingTicks > requesterGoneTicks;
    }

    private boolean isStocked(MinecraftClient mc, KitLoadout loadout) {
        return KitRestockPlan.compare(loadout, heldCounts(mc, loadout)).isReady();
    }

    /** Counts, per loadout item id, how many the bot currently holds in its main inventory. */
    private Map<String, Integer> heldCounts(MinecraftClient mc, KitLoadout loadout) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (mc.player == null || loadout == null) return counts;

        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            if (loadout.items().stream().noneMatch(spec -> spec.itemId().equals(id))) continue;
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private int remainingLoadoutItems(MinecraftClient mc, KitLoadoutRegistry loadouts, String kitName) {
        if (mc.player == null || loadouts == null) return 0;
        KitLoadout loadout = loadouts.find(kitName);
        if (loadout == null || loadout.isEmpty()) return 0;

        int remaining = 0;
        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            if (loadout.items().stream().anyMatch(spec -> spec.itemId().equals(id))) remaining++;
        }
        return remaining;
    }

    /** Drops one whole stack that belongs to the kit, from the main inventory. */
    private void dropOneLoadoutItem(MinecraftClient mc, KitLoadoutRegistry loadouts, String kitName) {
        if (mc.player == null || loadouts == null) return;
        KitLoadout loadout = loadouts.find(kitName);
        if (loadout == null) return;

        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            if (loadout.items().stream().anyMatch(spec -> spec.itemId().equals(id))) {
                InvUtils.drop().slot(slot);
                return;
            }
        }
    }

    private static String itemId(ItemStack stack) {
        return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    /** A convenience for the module: a chat sender bound to the live network handler. */
    public static ChatSender networkSender(MinecraftClient mc) {
        return line -> {
            if (mc.player != null && mc.player.networkHandler != null) {
                mc.player.networkHandler.sendChatMessage(line);
            }
        };
    }

    /** True when the client is connected to 6b6t, used to warn when the module is on elsewhere. */
    public static boolean onSixbt() {
        return dev.basehunt.bhclient.core.sixbt.SixbtCommand.isSixbtServer(ServerUtils.serverAddress());
    }

    /** A no-op listener for tests and for when the module does not want callbacks. */
    public static Listener silentListener() {
        return new Listener() {
            @Override public void onRequest(String requester, String kit) { }

            @Override public void onStarted(String requester, String kit) { }

            @Override public void onFinished(String requester, String kit) { }

            @Override public void onFailed(String requester, String reason) { }

            @Override public void onRestockNeeded(String kit, int missingItems) { }
        };
    }

    /** A consumer-based listener, so the module can forward to its own logging in one place. */
    public static Listener listener(Consumer<String> log) {
        return new Listener() {
            @Override public void onRequest(String requester, String kit) {
                log.accept("request from %s for %s".formatted(requester, kit));
            }

            @Override public void onStarted(String requester, String kit) {
                log.accept("delivering %s to %s".formatted(kit, requester));
            }

            @Override public void onFinished(String requester, String kit) {
                log.accept("delivered %s to %s".formatted(kit, requester));
            }

            @Override public void onFailed(String requester, String reason) {
                log.accept("delivery to %s failed: %s".formatted(requester, reason));
            }

            @Override public void onRestockNeeded(String kit, int missingItems) {
                log.accept("not stocked for %s (%d item(s) missing)".formatted(kit, missingItems));
            }
        };
    }
}
