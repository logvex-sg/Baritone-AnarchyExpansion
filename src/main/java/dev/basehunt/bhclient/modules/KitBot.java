package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.kit.Kit;
import dev.basehunt.bhclient.core.kit.KitRegistry;
import dev.basehunt.bhclient.core.kit.KitResponses;
import dev.basehunt.bhclient.systems.KitBotStore;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claims server kits automatically and tracks their cooldowns.
 *
 * <p>A kit claim is a chat line such as {@code /kit travel}. The server enforces the cooldown, but the client
 * can only see it through the reply, so the bot does two things: it never sends a claim it believes is still
 * on cooldown, and it listens to the chat reply to learn whether the claim succeeded, was refused for cooldown,
 * or was denied. That reply is the honest signal - without it the bot would either spam the command or wait
 * forever.
 *
 * <p>It runs on the normal chat path, so it works exactly as if the player typed the command. There is no
 * packet forging and no cooldown bypass; a server that refuses the kit gets a report, not a retry loop.
 */
public class KitBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgKits = settings.createGroup("Kits");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print kit activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> claimOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("claim-on-join")
        .description("Try to claim every ready kit when you join a server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> joinDelay = sgGeneral.add(new IntSetting.Builder()
        .name("join-delay")
        .description("Ticks to wait after joining before claiming, so the server has settled.")
        .defaultValue(60)
        .min(0)
        .sliderRange(0, 400)
        .build()
    );

    private final Setting<Boolean> claimOnCooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("recheck-cooldown")
        .description("Re-attempt a kit once its local cooldown has elapsed while online.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> claimDelay = sgGeneral.add(new IntSetting.Builder()
        .name("claim-delay")
        .description("Ticks between two kit claims, so the server is not flooded.")
        .defaultValue(40)
        .min(5)
        .sliderRange(5, 200)
        .build()
    );

    private final Setting<List<String>> kitLines = sgKits.add(new StringListSetting.Builder()
        .name("kits")
        .description("Kits as name:command:cooldown-seconds, e.g. travel:/kit travel:3600.")
        .defaultValue(List.of(
            "travel:/kit travel:3600",
            "pvp:/kit pvp:3600",
            "build:/kit build:3600",
            "starter:/kit starter:3600"
        ))
        .build()
    );

    private final KitRegistry registry = new KitRegistry();
    private final Map<String, Long> pendingClaims = new LinkedHashMap<>();

    private int joinCooldown;
    private int claimCooldown;
    private final List<String> claimQueue = new ArrayList<>();

    public KitBot() {
        super(BaseHuntingAddon.CATEGORY, "kit-bot",
            "Claims server kits automatically and tracks their cooldowns.");
    }

    @Override
    public void onActivate() {
        reloadRegistry();
        joinCooldown = -1;
        claimCooldown = 0;
        claimQueue.clear();
        pendingClaims.clear();
    }

    @Override
    public void onDeactivate() {
        claimQueue.clear();
        pendingClaims.clear();
    }

    /** Rebuilds the live registry from the saved store and the module's own kit list. */
    private void reloadRegistry() {
        registry.clear();

        KitBotStore store = KitBotStore.get();
        String server = ServerUtils.serverAddress();
        if (store != null) {
            KitRegistry saved = store.registryFor(server);
            registry.registerAll(saved.all());
        }

        // The module's own list wins over the stored defaults, so a user can edit the command or cooldown here.
        for (String line : kitLines.get()) {
            Kit kit = parseKitLine(line);
            if (kit != null) registry.register(kit);
        }
    }

    /** Parses {@code name:command:cooldown}. A missing command defaults to {@code /kit <name>}. */
    static Kit parseKitLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split(":", 3);
        String name = parts[0].trim();
        if (name.isEmpty()) return null;

        String command = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "/kit " + name;
        int cooldown = 3600;
        if (parts.length > 2) {
            try {
                cooldown = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException ignored) {
                // Keep the default rather than failing the whole kit.
            }
        }
        return Kit.of(name, command, cooldown);
    }

    public KitRegistry registry() {
        return registry;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reloadRegistry();
        if (claimOnJoin.get()) joinCooldown = joinDelay.get();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (joinCooldown > 0) {
            joinCooldown--;
            if (joinCooldown == 0) queueReadyKits();
            return;
        }

        if (claimCooldown > 0) {
            claimCooldown--;
            return;
        }

        if (claimQueue.isEmpty()) {
            if (claimOnCooldown.get()) queueReadyKits();
            return;
        }

        String name = claimQueue.remove(0);
        claim(name);
        claimCooldown = claimDelay.get();
    }

    /** Queues every kit whose local cooldown has elapsed. */
    private void queueReadyKits() {
        long now = System.currentTimeMillis();
        for (Kit kit : registry.readyKits(now)) {
            if (!claimQueue.contains(kit.name())) claimQueue.add(kit.name());
        }
    }

    /**
     * Claims a kit. Returns a short result message. A kit still on local cooldown is refused here, before any
     * chat line is sent, so the server is never spammed with a claim it will reject.
     */
    public String claim(String name) {
        Kit kit = registry.find(name);
        if (kit == null) return "no kit named '" + name + "'";

        long now = System.currentTimeMillis();
        long remaining = registry.cooldownRemaining(kit.name(), now);
        if (remaining > 0) {
            publish(AutomationEvent.Type.KIT_ON_COOLDOWN, Map.of("kit", kit.name(),
                "remaining", String.valueOf(remaining)));
            if (chatFeedback.get()) warning("%s is on cooldown for another %d:%02d.", kit.name(),
                remaining / 60000, (remaining / 1000) % 60);
            return "on cooldown";
        }

        if (mc.player == null || mc.player.networkHandler == null) return "not connected";

        mc.player.networkHandler.sendChatMessage(kit.claimLine());
        pendingClaims.put(kit.name(), now);

        publish(AutomationEvent.Type.KIT_CLAIM_ATTEMPTED, Map.of("kit", kit.name(), "command", kit.claimLine()));
        if (chatFeedback.get()) info("Claiming %s.", kit.name());
        return "claimed " + kit.name();
    }

    /** Claims every ready kit now. Returns a short result message. */
    public String claimAll() {
        long now = System.currentTimeMillis();
        List<Kit> ready = registry.readyKits(now);
        if (ready.isEmpty()) return "no kits are ready";

        for (Kit kit : ready) if (!claimQueue.contains(kit.name())) claimQueue.add(kit.name());
        return "queued " + ready.size() + " kit(s)";
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (pendingClaims.isEmpty()) return;

        String text = event.getMessage().getString();
        KitResponses.Verdict verdict = KitResponses.classify(text);
        if (!KitResponses.resolves(verdict)) return;

        // The reply may not name the kit, so it resolves the oldest pending claim.
        String kitName = pendingClaims.keySet().iterator().next();
        pendingClaims.remove(kitName);

        long now = System.currentTimeMillis();
        KitBotStore store = KitBotStore.get();
        String server = ServerUtils.serverAddress();

        switch (verdict) {
            case GRANTED -> {
                registry.recordClaim(kitName, now);
                if (store != null) store.recordClaim(server, kitName, now);
                publish(AutomationEvent.Type.KIT_CLAIM_SUCCEEDED, Map.of("kit", kitName));
                if (chatFeedback.get()) info("Kit %s claimed.", kitName);
            }
            case COOLDOWN -> {
                // A server-side cooldown we did not know about: adopt it so we stop trying.
                registry.recordClaim(kitName, now);
                if (store != null) store.recordClaim(server, kitName, now);
                publish(AutomationEvent.Type.KIT_ON_COOLDOWN, Map.of("kit", kitName));
                if (chatFeedback.get()) warning("Server reports %s on cooldown.", kitName);
            }
            case DENIED -> {
                publish(AutomationEvent.Type.KIT_CLAIM_FAILED, Map.of("kit", kitName, "reason", text));
                if (chatFeedback.get()) warning("Kit %s was refused: %s", kitName, text);
            }
            default -> { }
        }
    }

    /** A one-line status for the HUD and terminal. */
    public String statusLine() {
        return registry.status(System.currentTimeMillis());
    }

    /** Detailed lines for the terminal. */
    public List<String> describe() {
        return registry.describe(System.currentTimeMillis());
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
