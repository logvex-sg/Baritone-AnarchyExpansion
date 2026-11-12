package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.kit.Kit;
import dev.basehunt.bhclient.core.kit.KitRegistry;
import dev.basehunt.bhclient.core.kit.KitResponses;
import dev.basehunt.bhclient.core.sixbt.ChestKitSearch;
import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.core.sixbt.KitLoadoutRegistry;
import dev.basehunt.bhclient.core.sixbt.KitMessages;
import dev.basehunt.bhclient.core.sixbt.KitRequestFlow;
import dev.basehunt.bhclient.core.sixbt.KitRequestPolicy;
import dev.basehunt.bhclient.core.sixbt.KitRestockPlan;
import dev.basehunt.bhclient.core.sixbt.SixbtChat;
import dev.basehunt.bhclient.core.sixbt.TpaPolicy;
import dev.basehunt.bhclient.systems.KitBotStore;
import dev.basehunt.bhclient.systems.KitChestSource;
import dev.basehunt.bhclient.systems.KitDeliveryService;
import dev.basehunt.bhclient.systems.KitLoadoutStore;
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
import java.util.Optional;

/**
 * The 6b6t kit bot: either a kit <em>claimer</em> or a kit <em>maker</em>.
 *
 * <p>Two roles share this module because they are the same job seen from the two sides of a 6b6t kit.
 * <em>Claiming</em> asks the server for a kit with {@code /kit travel} and tracks the cooldown the server
 * enforces. <em>Making</em> is the delivery-bot role: on 6b6t kits are handed out by players, so a request
 * such as {@code !kit pvp} in chat is answered by teleporting to the requester, dropping the loadout and
 * killing yourself to reset. The addon can be that bot.
 *
 * <p>Delivery is deliberately <strong>off by default</strong>: it makes the player teleport to strangers and
 * then kill itself, which nobody should get by accident. Claiming keeps its original default-on behaviour so
 * an existing install is not silently changed.
 *
 * <p>The module itself only wires the two features to their stores and settings; the decisions live in the
 * pure {@code core.sixbt} classes and the world-touching parts live in {@link KitDeliveryService}. That split
 * is what keeps the state machine testable and this class readable.
 */
public class KitBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgKits = settings.createGroup("Kits");
    private final SettingGroup sgDelivery = settings.createGroup("Kit Delivery (6b6t)");

    // Claiming

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

    // Delivery

    private final Setting<Boolean> deliveryEnabled = sgDelivery.add(new BoolSetting.Builder()
        .name("delivery-enabled")
        .description("Act as a 6b6t kit maker: answer kit requests by teleporting, delivering and killing yourself. Off by default.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> requestPrefixes = sgDelivery.add(new StringListSetting.Builder()
        .name("request-prefixes")
        .description("Chat prefixes that mark a kit request, e.g. >lxkit so '>lxkit totem' works.")
        .defaultValue(List.of(">lxkit"))
        .build()
    );

    private final Setting<Boolean> bareRequests = sgDelivery.add(new BoolSetting.Builder()
        .name("bare-requests")
        .description("Accept '<prefix> <kit>' with no 'kit' keyword, e.g. >lxkit totem. Off needs '>lxkit kit totem'.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> arrivalRadius = sgDelivery.add(new IntSetting.Builder()
        .name("arrival-radius")
        .description("Blocks within which the requester counts as arrived after the teleport.")
        .defaultValue(6)
        .min(2)
        .sliderRange(2, 24)
        .build()
    );

    private final Setting<Integer> requestCooldown = sgDelivery.add(new IntSetting.Builder()
        .name("request-cooldown")
        .description("Seconds a player must wait before asking you again.")
        .defaultValue(600)
        .min(0)
        .sliderRange(0, 3600)
        .build()
    );

    private final Setting<Integer> tpaTimeout = sgDelivery.add(new IntSetting.Builder()
        .name("tpa-timeout")
        .description("Seconds to wait for a requester to accept the teleport before giving up.")
        .defaultValue(30)
        .min(5)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Integer> queueCapacity = sgDelivery.add(new IntSetting.Builder()
        .name("queue-capacity")
        .description("How many kit requests to hold at once.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> confirmDelay = sgDelivery.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Seconds to hold position after dropping, so the requester can pick the kit up.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 30)
        .build()
    );

    private final Setting<List<String>> messageLines = sgDelivery.add(new StringListSetting.Builder()
        .name("messages")
        .description("Whisper replies as kind:text. Kinds: grabbing, not_whitelisted, unknown_kit, "
            + "cooldown, queue_full, queued, delivered, failed. Placeholders: {player} {kit} {place} "
            + "{minutes} {max}.")
        .defaultValue(List.of(
            "grabbing:Grabbing the kit, please wait.",
            "not_whitelisted:Not whitelisted (contact logvex).",
            "unknown_kit:Kit doesnt exist.",
            "cooldown:Error, {minutes} minute cooldown active.",
            "queue_full:Error, too many kits (max {max}).",
            "queued:You are in the queue, please wait. You are in place {place}.",
            "delivered:Kit delivered.",
            "failed:Error, the delivery could not be completed."
        ))
        .build()
    );

    private final Setting<Boolean> requireStock = sgDelivery.add(new BoolSetting.Builder()
        .name("require-stock")
        .description("Refuse a delivery when you are not carrying the whole loadout.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> acceptTpa = sgDelivery.add(new BoolSetting.Builder()
        .name("accept-incoming-tpa")
        .description("Auto-accept incoming /tpa requests from allowed players while delivering.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> tpaAllowList = sgDelivery.add(new StringListSetting.Builder()
        .name("tpa-allow-list")
        .description("Players whose incoming /tpa requests are accepted.")
        .defaultValue(List.of())
        .build()
    );

    // Who may use the bot

    private final Setting<List<String>> whitelist = sgDelivery.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Only these players may request a kit. Empty means nobody can (see allow-anyone).")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<List<String>> blacklist = sgDelivery.add(new StringListSetting.Builder()
        .name("blacklist")
        .description("These players are never served, even when allow-anyone is on.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Boolean> allowAnyone = sgDelivery.add(new BoolSetting.Builder()
        .name("allow-anyone")
        .description("Serve every requester, not just the whitelist. Only for a bot parked in a safe spot.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> chestRadius = sgDelivery.add(new IntSetting.Builder()
        .name("chest-radius")
        .description("How far to look for a registered container holding the kit, in blocks.")
        .defaultValue(24)
        .min(4)
        .sliderRange(4, 96)
        .build()
    );

    private final KitRegistry registry = new KitRegistry();
    private final Map<String, Long> pendingClaims = new LinkedHashMap<>();

    private KitLoadoutRegistry loadouts = new KitLoadoutRegistry();
    private KitDeliveryService delivery;
    private KitRequestPolicy requestPolicy = KitRequestPolicy.denyAll();

    private int joinCooldown;
    private int claimCooldown;
    private final List<String> claimQueue = new ArrayList<>();

    public KitBot() {
        super(BaseHuntingAddon.CATEGORY, "kit-bot",
            "Claims server kits, or acts as a 6b6t kit maker that delivers kits on request.");
    }

    @Override
    public void onActivate() {
        reloadRegistry();
        reloadLoadouts();
        joinCooldown = -1;
        claimCooldown = 0;
        claimQueue.clear();
        pendingClaims.clear();
        rebuildDelivery();
    }

    @Override
    public void onDeactivate() {
        claimQueue.clear();
        pendingClaims.clear();
        if (delivery != null) delivery.clear();
        delivery = null;
    }

    // Claiming

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
        reloadLoadouts();
        rebuildDelivery();
        if (claimOnJoin.get()) joinCooldown = joinDelay.get();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        tickDelivery();
        tickClaiming();
    }

    private void tickClaiming() {
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
        String text = event.getMessage().getString();

        if (!pendingClaims.isEmpty()) {
            KitResponses.Verdict verdict = KitResponses.classify(text);
            if (KitResponses.resolves(verdict)) resolveClaim(text, verdict);
        }

        if (deliveryEnabled.get()) handleDeliveryChat(text);
    }

    private void resolveClaim(String text, KitResponses.Verdict verdict) {
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

    // Delivery

    /** Loads the persisted loadouts for the current server. */
    public void reloadLoadouts() {
        KitLoadoutStore store = KitLoadoutStore.get();
        loadouts = store == null ? KitLoadoutRegistry.defaults()
            : store.registryFor(ServerUtils.serverAddress());
    }

    /** Rebuilds the delivery service from the current settings, discarding any in-flight delivery. */
    public void rebuildDelivery() {
        if (delivery != null) delivery.clear();
        delivery = new KitDeliveryService(queueCapacity.get(), requestCooldown.get() * 1000L, new KitDeliveryService.Listener() {
            @Override
            public void onRequest(String requester, String kit) {
                publish(AutomationEvent.Type.KIT_REQUEST_RECEIVED,
                    Map.of("requester", requester, "kit", kit));
                if (chatFeedback.get()) info("Kit request from %s for %s.", requester, kit);
            }

            @Override
            public void onStarted(String requester, String kit) {
                publish(AutomationEvent.Type.KIT_DELIVERY_STARTED,
                    Map.of("requester", requester, "kit", kit));
                if (chatFeedback.get()) info("Delivering %s to %s.", kit, requester);
            }

            @Override
            public void onFinished(String requester, String kit) {
                publish(AutomationEvent.Type.KIT_DELIVERY_COMPLETED,
                    Map.of("requester", requester, "kit", kit));
                if (chatFeedback.get()) info("Delivered %s to %s.", kit, requester);
            }

            @Override
            public void onFailed(String requester, String reason) {
                publish(AutomationEvent.Type.KIT_DELIVERY_FAILED,
                    Map.of("requester", requester == null ? "" : requester, "reason", reason == null ? "" : reason));
                if (chatFeedback.get()) warning("Delivery to %s failed: %s", requester, reason);
            }

            @Override
            public void onRestockNeeded(String kit, int missingItems) {
                publish(AutomationEvent.Type.KIT_RESTOCK_NEEDED,
                    Map.of("kit", kit, "missing", String.valueOf(missingItems)));
                if (chatFeedback.get()) warning("Not stocked for %s - %d item(s) missing.", kit, missingItems);
            }
        });
        delivery.setArrivalRadius(arrivalRadius.get());
        delivery.setTpaTimeoutTicks(tpaTimeout.get() * 20);
        delivery.setMessages(KitMessages.fromLines(messageLines.get()));
        delivery.setConfirmTicks(confirmDelay.get() * 20);
        requestPolicy = new KitRequestPolicy(whitelist.get(), blacklist.get(), allowAnyone.get());
    }

    private void tickDelivery() {
        if (!deliveryEnabled.get() || delivery == null) return;
        delivery.tick(mc, KitDeliveryService.networkSender(mc), loadouts, requireStock.get());
    }

    /** Reads a chat line for a kit request and an incoming /tpa, when delivery is on. */
    private void handleDeliveryChat(String text) {
        // Deliberately no kit filter: an unknown kit name still needs an answer ("Kit doesnt exist.").
        Optional<SixbtChat.Request> request = SixbtChat.findRequest(text, requestPrefixes.get(),
            bareRequests.get(), List.of());
        if (request.isPresent()) {
            SixbtChat.Request found = request.get();
            String self = ServerUtils.playerName();
            if (found.requester().equalsIgnoreCase(self)) return;

            long now = System.currentTimeMillis();
            KitRequestPolicy.Decision decision = requestPolicy.decide(found.requester());
            boolean kitExists = loadouts.contains(found.kit());
            String refusal = decision == KitRequestPolicy.Decision.ALLOW && kitExists
                ? delivery.wouldRefuse(found.requester(), now) : null;

            KitRequestFlow.Reply reply = KitRequestFlow.evaluate(decision, kitExists, refusal,
                delivery.positionOf(found.requester()), delivery.cooldownSecondsLeft(found.requester(), now),
                queueCapacity.get());
            // GRABBING is whispered by the delivery service when it actually starts the hand-over; sending
            // it here too would double the message. Every other reply belongs here.
            if (reply.kind() != KitMessages.Kind.GRABBING) whisper(reply, found.requester(), found.kit());

            if (decision != KitRequestPolicy.Decision.ALLOW || !kitExists || refusal != null) {
                if (chatFeedback.get()) {
                    warning("Kit request from %s for %s ignored (%s).", found.requester(), found.kit(),
                        decision != KitRequestPolicy.Decision.ALLOW ? "not whitelisted"
                            : !kitExists ? "unknown kit" : refusal);
                }
                return;
            }

            // Accepted. Check storage holds the kit before teleporting anywhere.
            KitLoadout loadout = loadouts.find(found.kit());
            ChestKitSearch.Result source = KitChestSource.find(loadout, chestRadius.get());
            if (!source.complete()) {
                // Do not leave the requester hanging after "grabbing": tell them it failed.
                whisper(KitRequestFlow.failed(), found.requester(), found.kit());
                if (chatFeedback.get()) warning("Not stocked for %s: %s", found.kit(), source.summary());
                publish(AutomationEvent.Type.KIT_RESTOCK_NEEDED,
                    Map.of("kit", found.kit(), "detail", source.summary()));
                return;
            }

            String reason = delivery.offer(found.requester(), found.kit(), now);
            if (reason != null && chatFeedback.get()) {
                warning("Ignoring kit request from %s: %s.", found.requester(), reason);
            }
            return;
        }

        if (acceptTpa.get()) {
            SixbtChat.findTpaRequest(text).ifPresent(player -> {
                TpaPolicy policy = new TpaPolicy(tpaAllowList.get(), List.of(), false);
                TpaPolicy.Decision decision = policy.decide(player);
                if (decision == TpaPolicy.Decision.ACCEPT) {
                    sendLine("/tpy " + player);
                    publish(AutomationEvent.Type.SIXBT_TPA_ACCEPTED, Map.of("player", player));
                } else if (decision == TpaPolicy.Decision.DECLINE) {
                    sendLine("/tpn " + player);
                    publish(AutomationEvent.Type.SIXBT_TPA_DECLINED, Map.of("player", player));
                }
            });
        }
    }

    /** Sends one of the configurable whisper replies to a requester. */
    private void whisper(KitRequestFlow.Reply reply, String requester, String kit) {
        if (reply == null || delivery == null) return;
        java.util.Map<String, String> vars = new java.util.LinkedHashMap<>(reply.vars());
        vars.put("player", requester);
        vars.put("kit", kit == null ? "" : kit);
        String line = delivery.messages().whisper(reply.kind(), requester, vars);
        if (!line.isEmpty()) sendLine(line);
    }

    // Public control surface (used by the terminal, the menu and the commands)

    public KitLoadoutRegistry loadouts() {
        return loadouts;
    }

    /** Whether the delivery (kit-maker) role is switched on. */
    public boolean isDeliveryEnabled() {
        return deliveryEnabled.get();
    }

    /** Switches the delivery role on or off and rebuilds the service. */
    public void setDeliveryEnabled(boolean value) {
        deliveryEnabled.set(value);
        rebuildDelivery();
    }

    /** Whether kit activity is printed to chat. */
    public boolean isChatFeedback() {
        return chatFeedback.get();
    }

    public void setChatFeedback(boolean value) {
        chatFeedback.set(value);
    }

    /** Adds an item to a kit loadout and persists it. */
    public String addLoadoutItem(String kit, String item, int count) {
        KitLoadout.ItemSpec spec = new KitLoadout.ItemSpec(item, count);
        if (!spec.isValid()) return "invalid item or count";

        KitLoadout loadout = loadouts.findOrDefault(kit).with(spec);
        loadouts.put(loadout);
        KitLoadoutStore store = KitLoadoutStore.get();
        if (store != null) store.put(ServerUtils.serverAddress(), loadout);
        return "added " + spec.describe() + " to " + loadout.name();
    }

    /** Removes every line for an item from a kit loadout and persists it. */
    public String removeLoadoutItem(String kit, String item) {
        KitLoadout loadout = loadouts.find(kit);
        if (loadout == null) return "no loadout named '" + kit + "'";
        KitLoadout updated = loadout.without(item);
        loadouts.put(updated);
        KitLoadoutStore store = KitLoadoutStore.get();
        if (store != null) store.put(ServerUtils.serverAddress(), updated);
        return "removed " + item + " from " + updated.name();
    }

    /** Creates an empty loadout. */
    public String createLoadout(String kit) {
        KitLoadout loadout = KitLoadout.empty(kit);
        loadouts.put(loadout);
        KitLoadoutStore store = KitLoadoutStore.get();
        if (store != null) store.put(ServerUtils.serverAddress(), loadout);
        return "created loadout " + kit;
    }

    /** Deletes a loadout. */
    public String deleteLoadout(String kit) {
        if (!loadouts.contains(kit)) return "no loadout named '" + kit + "'";
        loadouts.remove(kit);
        KitLoadoutStore store = KitLoadoutStore.get();
        if (store != null) store.remove(ServerUtils.serverAddress(), kit);
        return "deleted loadout " + kit;
    }

    /** The shortfall between the loadout and what the player is carrying. */
    public KitRestockPlan.Result restockFor(String kit) {
        KitLoadout loadout = loadouts.find(kit);
        if (loadout == null) return new KitRestockPlan.Result(List.of());
        return KitRestockPlan.compare(loadout, carriedCounts(loadout));
    }

    private Map<String, Integer> carriedCounts(KitLoadout loadout) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (mc.player == null || loadout == null) return counts;

        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            if (loadout.items().stream().noneMatch(spec -> spec.itemId().equalsIgnoreCase(id))) continue;
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** Manually delivers a kit to a named player, bypassing the queue. */
    public String deliverTo(String player, String kit) {
        if (delivery == null) rebuildDelivery();
        if (!loadouts.contains(kit)) return "no loadout named '" + kit + "'";
        if (delivery.busy()) return "already delivering " + delivery.statusLine();
        String reason = delivery.offer(player, kit, System.currentTimeMillis());
        if (reason != null) return "refused: " + reason;
        return "queued " + kit + " for " + player;
    }

    /** Clears the delivery queue and abandons any delivery in progress. */
    public String clearQueue() {
        if (delivery != null) delivery.clear();
        return "delivery queue cleared";
    }

    public boolean isDelivering() {
        return deliveryEnabled.get() && delivery != null && delivery.busy();
    }

    public String deliveryStatusLine() {
        if (delivery == null) return "delivery not running";
        if (!deliveryEnabled.get()) return "delivery disabled";
        return delivery.statusLine();
    }

    /** Detailed delivery lines for the terminal. */
    public List<String> describeDelivery() {
        List<String> lines = new ArrayList<>();
        lines.add("delivery: " + (deliveryEnabled.get() ? "enabled" : "disabled"));
        lines.add("access: " + requestPolicy.statusLine());
        if (delivery != null) lines.addAll(delivery.describe());
        lines.add("loadouts: " + String.join(", ", loadouts.names()));
        return lines;
    }

    // Access control (who may use the bot)

    /** The current access policy, rebuilt from the settings on every delivery rebuild. */
    public KitRequestPolicy requestPolicy() {
        return requestPolicy;
    }

    /** A one-line summary of who may request kits. */
    public String whitelistStatusLine() {
        return requestPolicy.statusLine();
    }

    /** True when the named player would be served. */
    public boolean isWhitelisted(String player) {
        return requestPolicy.isAllowed(player);
    }

    /** Adds a player to the whitelist and rebuilds the policy. Returns a short result message. */
    public String addToWhitelist(String player) {
        if (player == null || player.isBlank()) return "no player name given";
        String name = player.trim();
        List<String> list = new ArrayList<>(whitelist.get());
        if (list.stream().anyMatch(entry -> entry.equalsIgnoreCase(name))) {
            return name + " is already whitelisted";
        }
        list.add(name);
        whitelist.set(list);
        requestPolicy = new KitRequestPolicy(whitelist.get(), blacklist.get(), allowAnyone.get());
        return "whitelisted " + name;
    }

    /** Removes a player from the whitelist. Returns a short result message. */
    public String removeFromWhitelist(String player) {
        if (player == null || player.isBlank()) return "no player name given";
        String name = player.trim();
        List<String> list = new ArrayList<>(whitelist.get());
        if (list.removeIf(entry -> entry.equalsIgnoreCase(name))) {
            whitelist.set(list);
            requestPolicy = new KitRequestPolicy(whitelist.get(), blacklist.get(), allowAnyone.get());
            return "removed " + name + " from the whitelist";
        }
        return name + " is not whitelisted";
    }

    /** Blocks a player from ever being served. Returns a short result message. */
    public String addToBlacklist(String player) {
        if (player == null || player.isBlank()) return "no player name given";
        String name = player.trim();
        List<String> list = new ArrayList<>(blacklist.get());
        if (list.stream().anyMatch(entry -> entry.equalsIgnoreCase(name))) {
            return name + " is already blacklisted";
        }
        list.add(name);
        blacklist.set(list);
        requestPolicy = new KitRequestPolicy(whitelist.get(), blacklist.get(), allowAnyone.get());
        return "blacklisted " + name;
    }

    /** Unblocks a player. Returns a short result message. */
    public String removeFromBlacklist(String player) {
        if (player == null || player.isBlank()) return "no player name given";
        String name = player.trim();
        List<String> list = new ArrayList<>(blacklist.get());
        if (list.removeIf(entry -> entry.equalsIgnoreCase(name))) {
            blacklist.set(list);
            requestPolicy = new KitRequestPolicy(whitelist.get(), blacklist.get(), allowAnyone.get());
            return "unblocked " + name;
        }
        return name + " is not blacklisted";
    }

    /** Whether the bot serves anyone who asks. */
    public boolean isAllowAnyone() {
        return allowAnyone.get();
    }

    /** Opens or closes the bot to everyone, and rebuilds the policy. */
    public void setAllowAnyone(boolean value) {
        allowAnyone.set(value);
        requestPolicy = new KitRequestPolicy(whitelist.get(), blacklist.get(), allowAnyone.get());
    }

    /** Detailed access lines for the terminal and GUI. */
    public List<String> describeAccess() {
        List<String> lines = new ArrayList<>();
        lines.add("access: " + requestPolicy.statusLine());
        lines.add("whitelist: " + (whitelist.get().isEmpty() ? "(none)" : String.join(", ", whitelist.get())));
        lines.add("blacklist: " + (blacklist.get().isEmpty() ? "(none)" : String.join(", ", blacklist.get())));
        return lines;
    }

    // Messages and storage

    /** The active whisper catalogue. */
    public KitMessages messages() {
        return delivery == null ? KitMessages.fromLines(messageLines.get()) : delivery.messages();
    }

    /** Overrides one whisper template and persists it in the setting. Returns a short result message. */
    public String setMessage(String kindToken, String template) {
        KitMessages.Kind kind = KitMessages.parseKind(kindToken);
        if (kind == null) return "unknown message kind '" + kindToken + "'";
        if (template == null || template.isBlank()) return "no text given";

        String key = kind.name().toLowerCase(java.util.Locale.ROOT);
        List<String> lines = new ArrayList<>();
        boolean replaced = false;
        for (String line : messageLines.get()) {
            if (line != null && line.toLowerCase(java.util.Locale.ROOT).startsWith(key + ":")) {
                lines.add(key + ":" + template.trim());
                replaced = true;
            } else {
                lines.add(line);
            }
        }
        if (!replaced) lines.add(key + ":" + template.trim());

        messageLines.set(lines);
        if (delivery != null) delivery.setMessages(KitMessages.fromLines(lines));
        return key + " -> " + template.trim();
    }

    /** Every whisper as {@code kind:text} lines. */
    public List<String> describeMessages() {
        return messages().describe();
    }

    /** Finds which nearby registered container holds a kit, for the terminal and the GUI. */
    public ChestKitSearch.Result findKitSource(String kit) {
        KitLoadout loadout = loadouts.find(kit);
        if (loadout == null) return new ChestKitSearch.Result(null, 0, 0, 0, 0, 0, List.of());
        return KitChestSource.find(loadout, chestRadius.get());
    }

    /** A one-line report of where a kit is stored, or why it cannot be delivered. */
    public String describeKitSource(String kit) {
        if (!loadouts.contains(kit)) return "no loadout named '" + kit + "'";
        return findKitSource(kit).summary();
    }

    /** The configured chest search radius. */
    public int chestRadius() {
        return chestRadius.get();
    }

    private void sendLine(String line) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(line);
        }
    }

    // Status

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
