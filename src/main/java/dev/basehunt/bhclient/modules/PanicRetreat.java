package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.SafeSpotFinder;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.block.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When a threat is called, retreats to the safest spot nearby instead of freezing in the open.
 *
 * <p>An automated client that stops dead the moment danger appears is both obvious and dead. This module
 * reacts to a {@code THREAT_DETECTED} event by sampling the blocks around the player, scoring them with the
 * pure {@link SafeSpotFinder}, and walking - not sprinting, and not teleporting - to the best one. It takes
 * the {@link ControlResource#MOVEMENT} lease so it never fights a task that is already moving the player, and
 * it gives up rather than looping forever if no spot is worth using.
 *
 * <p>It never attacks and never builds; it only repositions. The choice of ground is a piece of tested logic,
 * and the module is the thin layer that reads the world to feed it.
 */
public class PanicRetreat extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-retreat")
        .description("React to a threat by moving to a safer spot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> sampleRadius = sgGeneral.add(new IntSetting.Builder()
        .name("sample-radius")
        .description("How far around the player to look for a safe spot.")
        .defaultValue(6)
        .min(2)
        .sliderRange(2, 16)
        .build()
    );

    private final Setting<Integer> minScore = sgGeneral.add(new IntSetting.Builder()
        .name("min-score")
        .description("Only retreat to a spot scoring at least this. Higher means pickier.")
        .defaultValue(55)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum time between retreats.")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 300)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Report when a retreat starts.")
        .defaultValue(true)
        .build()
    );

    private long lastRetreat;
    private boolean retreating;

    public PanicRetreat() {
        super(BaseHuntingAddon.CATEGORY, "panic-retreat",
            "Retreats to the safest nearby spot when a threat is called.");
    }

    @Override
    public void onActivate() {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.bus().subscribe(AutomationEvent.Type.THREAT_DETECTED, this::onThreat);
        lastRetreat = 0;
        retreating = false;
    }

    @Override
    public void onDeactivate() {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) {
            manager.bus().unsubscribe(AutomationEvent.Type.THREAT_DETECTED, this::onThreat);
            manager.releaseAll(name);
        }
        retreating = false;
    }

    private void onThreat(AutomationEvent event) {
        if (!enabled.get() || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRetreat < cooldownSeconds.get() * 1000L) return;

        SafeSpotFinder.Ranked best = SafeSpotFinder.best(sample());
        if (best == null || best.score() < minScore.get()) return;

        BlockPos target = new BlockPos(best.spot().x(), best.spot().y(), best.spot().z());
        if (!acquire()) return;

        dev.basehunt.bhclient.utils.BaritoneHelper.walkTo(target);
        retreating = true;
        lastRetreat = now;

        publish(AutomationEvent.Type.RETREAT_STARTED, Map.of(
            "to", best.spot().coords(), "reason", best.reason(),
            "score", Integer.toString(best.score())));

        if (chatFeedback.get()) {
            warning("Retreating to %s (%s, score %d).", best.spot().coords(), best.reason(),
                best.score());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!retreating) return;
        // The retreat ends when Baritone stops pathing; the lease is released so other modules can move again.
        if (!dev.basehunt.bhclient.utils.BaritoneHelper.isPathing()) {
            retreating = false;
            AutomationManager manager = AutomationManager.get();
            if (manager != null) manager.releaseAll(name);
        }
    }

    /** Samples candidate ground blocks around the player and describes each for the pure scorer. */
    private List<SafeSpotFinder.Spot> sample() {
        List<SafeSpotFinder.Spot> spots = new ArrayList<>();
        int radius = sampleRadius.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > radius * radius) continue;

                BlockPos ground = findGround(dx, dz);
                if (ground == null) continue;
                spots.add(describe(ground));
            }
        }
        return spots;
    }

    /** Finds a standable block at this column reasonably close to the player's feet height. */
    private BlockPos findGround(int dx, int dz) {
        int baseY = MathHelper.floor(mc.player.getY());
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos candidate = new BlockPos(
                MathHelper.floor(mc.player.getX()) + dx, baseY + dy, MathHelper.floor(mc.player.getZ()) + dz);
            BlockState below = mc.world.getBlockState(candidate);
            BlockState at = mc.world.getBlockState(candidate.up());
            if (!below.isAir() && below.getFluidState().isEmpty() && !impedes(at, candidate.up())) {
                return candidate;
            }
        }
        return null;
    }

    private SafeSpotFinder.Spot describe(BlockPos pos) {
        int covered = 0;
        for (BlockPos side : new BlockPos[] {pos.north(), pos.south(), pos.east(), pos.west()}) {
            if (impedes(mc.world.getBlockState(side), side)) covered++;
        }

        boolean overhead = impedes(mc.world.getBlockState(pos.up(2)), pos.up(2));
        boolean lava = touches(pos, Blocks.LAVA);
        boolean fire = touches(pos, Blocks.FIRE) || touches(pos, Blocks.MAGMA_BLOCK);
        boolean water = touches(pos, Blocks.WATER);

        int drop = 0;
        for (int dy = 1; dy <= 8; dy++) {
            if (!mc.world.getBlockState(pos.down(dy)).isAir()) {
                drop = dy - 1;
                break;
            }
        }

        int light = mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos.up());

        return new SafeSpotFinder.Spot(pos.getX(), pos.getY(), pos.getZ(), covered, overhead,
            lava, fire, water, drop, light);
    }

    /** True when a block physically impedes movement, via a non-empty collision shape (no deprecated solidity shortcut). */
    private boolean impedes(BlockState state, BlockPos pos) {
        return !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean touches(BlockPos pos, net.minecraft.block.Block block) {
        return mc.world.getBlockState(pos).isOf(block)
            || mc.world.getBlockState(pos.up()).isOf(block)
            || mc.world.getBlockState(pos.down()).isOf(block)
            || mc.world.getBlockState(pos.north()).isOf(block)
            || mc.world.getBlockState(pos.south()).isOf(block)
            || mc.world.getBlockState(pos.east()).isOf(block)
            || mc.world.getBlockState(pos.west()).isOf(block);
    }

    private boolean acquire() {
        AutomationManager manager = AutomationManager.get();
        if (manager == null) return true;
        return manager.tryAcquire(name, Priority.CRITICAL_SURVIVAL, Set.of(ControlResource.MOVEMENT)) != null;
    }

    public boolean isRetreating() {
        return retreating;
    }

    /** One-line summary for the HUD. */
    public String statusLine() {
        return retreating ? "retreating" : "watching";
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
