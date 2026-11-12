package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.systems.SixbtPlayerStore;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.util.List;
import java.util.Map;

/**
 * Records which players have been seen on 6b6t, where and when.
 *
 * <p>The existing {@code PlayerLogger} tracks players for base hunting, tied to the exploration workflow. This
 * is the 6b6t-specific view: a compact, server-keyed record of everyone who has crossed your path, so the kit
 * bot and the player-facing tools can answer "have I met this player before?" and "where were they last?".
 *
 * <p>It is bounded and evicting, because an anarchy server has effectively unlimited names. It records only
 * players actually present in the loaded world - it never invents a position - and separates the last known
 * spot from the current one, which is the only honest thing a client can do.
 */
public class SixbtPlayerTracker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between scans of the loaded world for players.")
        .defaultValue(40)
        .min(10)
        .sliderRange(10, 400)
        .build()
    );

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Print in chat when a player is seen for the first time on this server.")
        .defaultValue(true)
        .build()
    );

    private int timer;

    public SixbtPlayerTracker() {
        super(BaseHuntingAddon.CATEGORY, "6b6t-players",
            "Records 6b6t player sightings, with a searchable per-server history.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (--timer > 0) return;
        timer = scanInterval.get();

        SixbtPlayerStore store = SixbtPlayerStore.get();
        if (store == null) return;

        String server = ServerUtils.serverAddress();
        String dimension = ServerUtils.dimension();
        long now = System.currentTimeMillis();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            String name = player.getName().getString();

            boolean first = store.search(server, name).stream().noneMatch(r -> r.name.equalsIgnoreCase(name));
            store.record(server, name, dimension,
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ()),
                now);

            if (first) {
                publish(AutomationEvent.Type.PLAYER_SIGHTED, Map.of(
                    "player", name, "dimension", dimension,
                    "x", String.valueOf((int) Math.floor(player.getX())),
                    "z", String.valueOf((int) Math.floor(player.getZ()))));
                if (announce.get()) info("New player on this server: %s.", name);
            }
        }
    }

    public String statusLine() {
        SixbtPlayerStore store = SixbtPlayerStore.get();
        if (store == null) return "player store unavailable";
        int nearby = mc.world == null ? 0 : mc.world.getPlayers().size();
        return "%d recorded, %d nearby".formatted(store.size(), nearby);
    }

    public List<String> describe() {
        SixbtPlayerStore store = SixbtPlayerStore.get();
        if (store == null) return List.of("player store unavailable");
        List<String> lines = new java.util.ArrayList<>();
        lines.add(statusLine());
        for (SixbtPlayerStore.Record record : store.forServer(ServerUtils.serverAddress())) {
            lines.add("  " + record.describe());
        }
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
