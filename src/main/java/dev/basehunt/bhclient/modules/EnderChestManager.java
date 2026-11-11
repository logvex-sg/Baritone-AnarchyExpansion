package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.InventoryPlan;
import dev.basehunt.bhclient.core.logic.ItemCategory;
import dev.basehunt.bhclient.systems.EnderChestStore;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opens and organises ender chests using ordinary client interaction.
 *
 * <p>Everything here goes through normal vanilla paths: the player looks at the chest and interacts, the
 * server opens a screen handler, and items are moved with shift-clicks. No packets are forged and no server
 * behaviour is worked around, so on a server that restricts ender chest access this module simply reports
 * that the chest did not open rather than pretending it did.
 *
 * <p>Two design choices matter for safety. First, nothing is moved until the module is told to organise, and
 * the plan is logged before the first click, so an accidental activation cannot shuffle inventory. Second,
 * deposits never touch a category that is not explicitly selected, which is what keeps elytras, totems and
 * other precious items out of the chest unless the user asks for it.
 */
public class EnderChestManager extends Module {
    private static final int ENDER_CHEST_ROWS = 3;
    private static final int PLAYER_SLOTS = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDeposit = settings.createGroup("Deposit");
    private final SettingGroup sgWithdraw = settings.createGroup("Withdraw");
    private final SettingGroup sgReserve = settings.createGroup("Reserves");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print ender chest activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks between inventory moves, to let the server keep up.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> autoOrganise = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-organise")
        .description("Run the deposit/withdraw plan automatically each time an ender chest is opened.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rememberChests = sgGeneral.add(new BoolSetting.Builder()
        .name("remember-chests")
        .description("Record the coordinates of ender chests you open, for later navigation.")
        .defaultValue(true)
        .build()
    );

    // Deposit

    private final Setting<Boolean> depositElytra = deposit(sgDeposit, "deposit-elytra", "Deposit spare elytras.", false);
    private final Setting<Boolean> depositArmor = deposit(sgDeposit, "deposit-armor", "Deposit spare armour.", false);
    private final Setting<Boolean> depositTools = deposit(sgDeposit, "deposit-tools", "Deposit spare tools.", false);
    private final Setting<Boolean> depositFireworks = deposit(sgDeposit, "deposit-fireworks", "Deposit fireworks.", true);
    private final Setting<Boolean> depositFood = deposit(sgDeposit, "deposit-food", "Deposit excess food.", true);
    private final Setting<Boolean> depositTotems = deposit(sgDeposit, "deposit-totems", "Deposit spare totems.", false);
    private final Setting<Boolean> depositXp = deposit(sgDeposit, "deposit-xp", "Deposit XP supplies.", true);
    private final Setting<Boolean> depositBuilding = deposit(sgDeposit, "deposit-building", "Deposit building blocks.", true);
    private final Setting<Boolean> depositUtility = deposit(sgDeposit, "deposit-utility", "Deposit utility items.", true);
    private final Setting<Boolean> depositValuable = deposit(sgDeposit, "deposit-valuable", "Deposit valuable items.", false);

    // Withdraw

    private final Setting<Boolean> withdrawElytra = withdraw(sgWithdraw, "withdraw-elytra", "Withdraw elytras.", true);
    private final Setting<Boolean> withdrawArmor = withdraw(sgWithdraw, "withdraw-armor", "Withdraw armour.", false);
    private final Setting<Boolean> withdrawTools = withdraw(sgWithdraw, "withdraw-tools", "Withdraw tools.", false);
    private final Setting<Boolean> withdrawFireworks = withdraw(sgWithdraw, "withdraw-fireworks", "Withdraw fireworks.", true);
    private final Setting<Boolean> withdrawFood = withdraw(sgWithdraw, "withdraw-food", "Withdraw food.", true);
    private final Setting<Boolean> withdrawTotems = withdraw(sgWithdraw, "withdraw-totems", "Withdraw totems.", true);
    private final Setting<Boolean> withdrawXp = withdraw(sgWithdraw, "withdraw-xp", "Withdraw XP supplies.", false);
    private final Setting<Boolean> withdrawValuable = withdraw(sgWithdraw, "withdraw-valuable", "Withdraw valuable items.", false);

    // Reserves

    private final Setting<Integer> reserveFireworks = reserve(sgReserve, "reserve-fireworks", "Fireworks to keep while depositing.", 16);
    private final Setting<Integer> reserveFood = reserve(sgReserve, "reserve-food", "Food items to keep while depositing.", 16);
    private final Setting<Integer> reserveTotems = reserve(sgReserve, "reserve-totems", "Totems to keep while depositing.", 1);
    private final Setting<Integer> reserveElytra = reserve(sgReserve, "reserve-elytra", "Elytras to keep while depositing.", 1);

    // State

    private final List<InventoryPlan.Move> pendingMoves = new ArrayList<>();
    private int moveCooldown;
    private boolean organising;
    private BlockPos lastChest;
    private long lastChestSeen;
    private String lastFailure;
    private BlockPos targetChest;
    private boolean enderChestOpen;

    public EnderChestManager() {
        super(BaseHuntingAddon.CATEGORY, "ender-chest-manager",
            "Opens ender chests and organises inventory contents using normal client interaction.");
    }

    private Setting<Boolean> deposit(SettingGroup group, String name, String description, boolean defaultValue) {
        return group.add(new BoolSetting.Builder().name(name).description(description).defaultValue(defaultValue).build());
    }

    private Setting<Boolean> withdraw(SettingGroup group, String name, String description, boolean defaultValue) {
        return group.add(new BoolSetting.Builder().name(name).description(description).defaultValue(defaultValue).build());
    }

    private Setting<Integer> reserve(SettingGroup group, String name, String description, int defaultValue) {
        return group.add(new IntSetting.Builder().name(name).description(description).defaultValue(defaultValue)
            .min(0).sliderRange(0, 256).build());
    }

    @Override
    public void onActivate() {
        pendingMoves.clear();
        organising = false;
        moveCooldown = 0;
        lastFailure = null;
        enderChestOpen = false;
        targetChest = null;
    }

    @Override
    public void onDeactivate() {
        pendingMoves.clear();
        organising = false;
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.releaseAll(name);
    }

    /** True when an ender chest screen is currently open. */
    public boolean isEnderChestOpen() {
        return mc.currentScreen instanceof HandledScreen<?> handled && isEnderChestHandler(handled.getScreenHandler());
    }

    private boolean isEnderChestHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler generic && generic.getRows() == ENDER_CHEST_ROWS;
    }

    /** Called by the command to organise the currently open ender chest. */
    public boolean organise() {
        if (!isEnderChestOpen()) {
            lastFailure = "no ender chest is open";
            return false;
        }

        HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
        List<InventoryPlan.Move> plan = planFromHandler(screen.getScreenHandler());

        if (plan.isEmpty()) {
            if (chatFeedback.get()) info("Nothing to move.");
            return true;
        }

        pendingMoves.clear();
        pendingMoves.addAll(plan);
        organising = true;
        moveCooldown = tickDelay.get();

        if (chatFeedback.get()) {
            info("Planned %d move(s):", plan.size());
            for (String line : InventoryPlan.preview(plan)) info(" - %s", line);
        }

        return true;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        HandledScreen<?> handled = event.screen instanceof HandledScreen<?> screen ? screen : null;

        if (handled == null || !isEnderChestHandler(handled.getScreenHandler())) {
            // Any other screen - including the null screen shown when a container closes, or the inventory
            // screen replacing it - means the ender chest is gone. Report the close once and drop any
            // in-flight plan rather than clicking at whatever is on screen now.
            closeIfOpen();
            return;
        }

        enderChestOpen = true;
        publish(AutomationEvent.Type.ENDER_CHEST_OPENED, Map.of("position", ServerUtils.coordinates()));

        if (rememberChests.get()) rememberLastChest();

        if (autoOrganise.get()) {
            // The handler is not fully populated the instant the screen opens; wait a few ticks.
            pendingMoves.clear();
            pendingMoves.addAll(planFromHandler(handled.getScreenHandler()));
            organising = !pendingMoves.isEmpty();
            moveCooldown = tickDelay.get() + 4;
        }
    }

    private void closeIfOpen() {
        if (!enderChestOpen) return;

        enderChestOpen = false;
        organising = false;
        pendingMoves.clear();
        releaseInventory();
        publish(AutomationEvent.Type.ENDER_CHEST_CLOSED, Map.of());
    }

    /**
     * Builds the deposit and withdrawal plan from a live container handler.
     *
     * <p>The handler's first {@code rows * 9} slots belong to the chest and the rest to the player. The plan
     * uses each slot's own {@code id} so the move is valid for whichever container is open.
     */
    private List<InventoryPlan.Move> planFromHandler(ScreenHandler handler) {
        int containerSlots = containerSlotCount(handler);

        List<InventoryPlan.Entry> playerEntries = new ArrayList<>();
        List<InventoryPlan.Entry> chestEntries = new ArrayList<>();
        int freeSlots = 0;

        for (int index = 0; index < handler.slots.size(); index++) {
            Slot slot = handler.getSlot(index);
            ItemStack stack = slot.getStack();

            if (index < containerSlots) {
                if (!stack.isEmpty()) chestEntries.add(entryFor(slot, stack));
                continue;
            }

            if (index >= containerSlots + PLAYER_SLOTS) continue;

            if (stack.isEmpty()) freeSlots++;
            else playerEntries.add(entryFor(slot, stack));
        }

        List<InventoryPlan.Move> plan = new ArrayList<>();
        plan.addAll(InventoryPlan.planDeposit(playerEntries, depositCategories(), reserveMap()));
        plan.addAll(InventoryPlan.planWithdraw(chestEntries, withdrawCategories(), freeSlots));
        return plan;
    }

    private InventoryPlan.Entry entryFor(Slot slot, ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        return new InventoryPlan.Entry(slot.id, ItemCategory.classify(id), stack.getName().getString(), stack.getCount());
    }

    private int containerSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) return generic.getRows() * 9;
        // Shulker boxes and other small containers expose their size through the slot count.
        return Math.max(0, handler.slots.size() - PLAYER_SLOTS);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (targetChest != null) {
            if (!(mc.world.getBlockState(targetChest).getBlock() instanceof EnderChestBlock)) {
                // The chest is gone (mined or unloaded); stop rather than pathing to a stale coordinate.
                targetChest = null;
            } else if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(targetChest)) <= 4.5) {
                dev.basehunt.bhclient.utils.BaritoneHelper.stop();
                openAt(targetChest);
                targetChest = null;
            }
        }

        if (!organising) return;

        if (!isEnderChestOpen()) {
            // The screen closed mid-operation. Stop rather than clicking at whatever is on screen now.
            organising = false;
            pendingMoves.clear();
            releaseInventory();
            return;
        }

        // Only click slots while this module actually owns the inventory. A higher priority automation that
        // grabbed the inventory mid-plan takes precedence; the plan is dropped rather than raced.
        AutomationManager manager = AutomationManager.get();
        if (manager != null && manager.tryAcquire(name, Priority.INVENTORY_MANAGEMENT,
                java.util.Set.of(ControlResource.INVENTORY)) == null) {
            return;
        }

        if (moveCooldown > 0) {
            moveCooldown--;
            return;
        }

        if (pendingMoves.isEmpty()) {
            organising = false;
            releaseInventory();
            if (chatFeedback.get()) info("Ender chest organisation complete.");
            publish(AutomationEvent.Type.INVENTORY_ORGANISED, Map.of());
            return;
        }

        InventoryPlan.Move move = pendingMoves.remove(0);

        // Shift-click moves a whole stack between the player inventory and the container.
        InvUtils.shiftClick().slotId(move.slotId());
        moveCooldown = tickDelay.get();
    }

    /**
     * Opens the ender chest the player is looking at, if it is within reach.
     *
     * @return true when the interaction was sent; the screen opening is confirmed by {@link #onOpenScreen}
     */
    public boolean openLookingAt() {
        if (mc.player == null || mc.world == null) {
            lastFailure = "no world";
            return false;
        }

        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            lastFailure = "not looking at a block";
            return false;
        }

        BlockPos pos = blockHit.getBlockPos();
        if (!(mc.world.getBlockState(pos).getBlock() instanceof EnderChestBlock)) {
            lastFailure = "the block you are looking at is not an ender chest";
            return false;
        }

        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > 4.5) {
            lastFailure = "the ender chest is out of reach";
            return false;
        }

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
        mc.player.swingHand(Hand.MAIN_HAND);
        lastFailure = null;
        return true;
    }

    /** Opens a specific ender chest block that is already within reach. */
    public boolean openAt(BlockPos pos) {
        if (mc.player == null || mc.world == null) {
            lastFailure = "no world";
            return false;
        }

        if (!(mc.world.getBlockState(pos).getBlock() instanceof EnderChestBlock)) {
            lastFailure = "no ender chest at that position";
            return false;
        }

        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > 4.5) {
            lastFailure = "the ender chest is out of reach";
            return false;
        }

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        lastFailure = null;
        return true;
    }

    private void releaseInventory() {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.releaseAll(name);
    }

    /** The reason the last operation failed, for the command to report. */
    public String lastFailure() {
        return lastFailure;
    }

    public boolean isOrganising() {
        return organising;
    }

    public int pendingMoveCount() {
        return pendingMoves.size();
    }

    /** Ender chest positions recorded this session, newest first. */
    public List<EnderChestStore.Chest> knownChests() {
        EnderChestStore store = EnderChestStore.get();
        return store == null ? List.of() : store.all();
    }

    private void rememberLastChest() {
        if (mc.player == null) return;

        BlockPos chestPos = findNearestEnderChest(mc.player.getBlockPos());
        if (chestPos == null) return;

        EnderChestStore store = EnderChestStore.get();
        if (store == null) return;

        store.record(chestPos, ServerUtils.dimension(), ServerUtils.serverAddress());
        lastChest = chestPos;
        lastChestSeen = System.currentTimeMillis();
        publish(AutomationEvent.Type.ENDER_CHEST_FOUND, Map.of(
            "position", "%d %d %d".formatted(chestPos.getX(), chestPos.getY(), chestPos.getZ())));
    }

    private BlockPos findNearestEnderChest(BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (!(mc.world.getBlockState(pos).getBlock() instanceof EnderChestBlock)) continue;

                    double distance = origin.getSquaredDistance(pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private Set<ItemCategory> depositCategories() {
        Set<ItemCategory> set = EnumSet.noneOf(ItemCategory.class);
        if (depositElytra.get()) set.add(ItemCategory.ELYTRA);
        if (depositArmor.get()) set.add(ItemCategory.ARMOR);
        if (depositTools.get()) set.add(ItemCategory.TOOLS);
        if (depositFireworks.get()) set.add(ItemCategory.FIREWORKS);
        if (depositFood.get()) set.add(ItemCategory.FOOD);
        if (depositTotems.get()) set.add(ItemCategory.TOTEMS);
        if (depositXp.get()) set.add(ItemCategory.XP_SUPPLIES);
        if (depositBuilding.get()) set.add(ItemCategory.BUILDING_BLOCKS);
        if (depositUtility.get()) set.add(ItemCategory.UTILITY);
        if (depositValuable.get()) set.add(ItemCategory.VALUABLE);
        return set;
    }

    private Set<ItemCategory> withdrawCategories() {
        Set<ItemCategory> set = EnumSet.noneOf(ItemCategory.class);
        if (withdrawElytra.get()) set.add(ItemCategory.ELYTRA);
        if (withdrawArmor.get()) set.add(ItemCategory.ARMOR);
        if (withdrawTools.get()) set.add(ItemCategory.TOOLS);
        if (withdrawFireworks.get()) set.add(ItemCategory.FIREWORKS);
        if (withdrawFood.get()) set.add(ItemCategory.FOOD);
        if (withdrawTotems.get()) set.add(ItemCategory.TOTEMS);
        if (withdrawXp.get()) set.add(ItemCategory.XP_SUPPLIES);
        if (withdrawValuable.get()) set.add(ItemCategory.VALUABLE);
        return set;
    }

    private Map<ItemCategory, Integer> reserveMap() {
        return Map.of(
            ItemCategory.FIREWORKS, reserveFireworks.get(),
            ItemCategory.FOOD, reserveFood.get(),
            ItemCategory.TOTEMS, reserveTotems.get(),
            ItemCategory.ELYTRA, reserveElytra.get()
        );
    }

    /**
     * Walks to the closest ender chest recorded for this server and dimension, then opens it.
     *
     * <p>This uses Baritone's walk pathfinder, which is optional, so it reports a clear error rather than
     * throwing when Baritone is not installed. The chest is opened once the player is within reach; the
     * opening itself is confirmed by {@link #onOpenScreen}, not by this call.
     *
     * @return a human-readable outcome, or {@code null} when the route was started
     */
    public String navigateToNearest() {
        EnderChestStore store = EnderChestStore.get();
        if (store == null) return "the ender chest store is not loaded";

        if (mc.player == null || mc.world == null) return "no world";

        String dimension = ServerUtils.dimension();
        EnderChestStore.Chest best = null;
        double bestDistance = Double.MAX_VALUE;

        for (EnderChestStore.Chest chest : store.forCurrentServer()) {
            if (!chest.dimension.equals(dimension)) continue;

            double distance = chest.pos.getSquaredDistance(mc.player.getBlockPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chest;
            }
        }

        if (best == null) return "no ender chest recorded for this dimension";

        if (!dev.basehunt.bhclient.utils.BaritoneHelper.AVAILABLE) {
            return "Baritone is not installed, cannot navigate (chest is at %s)"
                .formatted(best.pos.toShortString());
        }

        targetChest = best.pos;
        dev.basehunt.bhclient.utils.BaritoneHelper.walkTo(best.pos);
        lastFailure = null;

        if (chatFeedback.get()) {
            info("Walking to ender chest at %s (%d blocks away).",
                best.pos.toShortString(), (int) Math.sqrt(bestDistance));
        }

        return null;
    }

    /** Short status line for the HUD and the status command. */
    public String statusLine() {
        if (isEnderChestOpen()) {
            return organising ? "open, %d move(s) pending".formatted(pendingMoves.size()) : "open";
        }
        return "closed, %d chest(s) recorded".formatted(knownChests().size());
    }

    public BlockPos lastChest() {
        return lastChest;
    }

    public long lastChestSeen() {
        return lastChestSeen;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
