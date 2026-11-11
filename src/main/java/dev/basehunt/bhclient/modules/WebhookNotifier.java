package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reports session lifecycle events to a Discord webhook: logins, logouts, deaths and player sightings.
 *
 * <p>Anarchy bases are found by noticing who is where and when. Because logouts are broadcast to everyone
 * on the server, tracking them lets you find where other players keep their gear: their logout spot is
 * very often a base or a stash.
 */
public class WebhookNotifier extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEvents = settings.createGroup("Events");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Also print notifications in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeCoordinates = sgGeneral.add(new BoolSetting.Builder()
        .name("include-coordinates")
        .description("Include your position in every notification.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeServer = sgGeneral.add(new BoolSetting.Builder()
        .name("include-server")
        .description("Include the server address in every notification.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> muted = sgGeneral.add(new BoolSetting.Builder()
        .name("muted")
        .description("Keep the module running but suppress every outgoing webhook.")
        .defaultValue(false)
        .build()
    );

    // Events

    private final Setting<Boolean> notifyJoin = sgEvents.add(new BoolSetting.Builder()
        .name("notify-join")
        .description("Notify when you join a server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyLeave = sgEvents.add(new BoolSetting.Builder()
        .name("notify-leave")
        .description("Notify when you disconnect.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyPlayerJoin = sgEvents.add(new BoolSetting.Builder()
        .name("notify-player-join")
        .description("Notify when another player joins the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyPlayerLeave = sgEvents.add(new BoolSetting.Builder()
        .name("notify-player-leave")
        .description("Notify when another player leaves, including where they were.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyPlayerNearby = sgEvents.add(new BoolSetting.Builder()
        .name("notify-player-nearby")
        .description("Notify when a player is within the radius below.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> nearbyRadius = sgEvents.add(new IntSetting.Builder()
        .name("nearby-radius")
        .description("Radius for nearby player notifications.")
        .defaultValue(512)
        .min(16)
        .sliderRange(16, 4096)
        .visible(notifyPlayerNearby::get)
        .build()
    );

    private final Setting<Integer> messageCooldown = sgEvents.add(new IntSetting.Builder()
        .name("player-cooldown-seconds")
        .description("Minimum gap between two notifications about the same player.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 600)
        .build()
    );

    // Automation events

    private final SettingGroup sgAutomation = settings.createGroup("Automation Events");

    private final Setting<Boolean> notifyStash = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-stash")
        .description("Notify when a stash is discovered or grows.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyStructure = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-structure")
        .description("Notify when an interesting structure is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyExploration = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-exploration")
        .description("Notify on route starts, completions, interruptions and exploration milestones.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyEquipment = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-equipment")
        .description("Notify when elytras are replaced or durability runs low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyMending = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-mending")
        .description("Notify when mending repairs are needed or completed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifyEnderChest = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-ender-chest")
        .description("Notify when ender chests are found, opened or organised.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyEmergency = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-emergency")
        .description("Notify on critical survival conditions and danger.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyErrors = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-errors")
        .description("Notify on internal system errors.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> notifyPlayerSightings = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-player-sightings")
        .description("Notify when a player is sighted for the first time, and when a logout spot is recorded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyStorage = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-storage")
        .description("Notify about sorting, deposits, withdrawals, restocks, shulkers and workflows.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyTasks = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-tasks")
        .description("Notify about queued automation tasks and their outcome, and about kit claims.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyChatCommands = sgAutomation.add(new BoolSetting.Builder()
        .name("notify-chat-commands")
        .description("Notify when a chat command is run, including refused ones.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> automationCooldown = sgAutomation.add(new IntSetting.Builder()
        .name("event-cooldown-seconds")
        .description("Minimum gap between two notifications of the same automation event type.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 600)
        .build()
    );

    // Webhook

    private final Setting<String> title = sgWebhook.add(new StringSetting.Builder()
        .name("embed-title-prefix")
        .description("Prefix added to the title of every embed.")
        .defaultValue("")
        .build()
    );

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Map<UUID, Long> lastNotified = new HashMap<>();
    private final Map<AutomationEvent.Type, Long> lastAutomation = new java.util.EnumMap<>(AutomationEvent.Type.class);
    private java.util.function.Consumer<AutomationEvent> automationListener;

    private long sessionStartedAt;
    private int sessionDeaths;

    public WebhookNotifier() {
        super(BaseHuntingAddon.CATEGORY, "webhook-notifier", "Sends session and player events to a Discord webhook.");
    }

    public boolean isMuted() {
        return muted.get();
    }

    /** Fires a plain test embed so a webhook URL can be verified from chat. */
    public void sendTest() {
        if (WebhookSettings.globallyMuted()) {
            error("Webhook output is muted, unmute to send a test.");
            return;
        }

        boolean sent = webhook.send(baseEmbed("Test message", 0x00FF88)
            .description("If you can read this, the webhook is configured correctly.")
            .field("Player", ServerUtils.playerName())
            .field("Position", ServerUtils.coordinates()));

        if (sent) info("Test webhook queued.");
        else error("Webhook is not enabled or the URL is blank.");
    }

    public boolean isSendingAllowed() {
        return !muted.get();
    }

    @Override
    public void onActivate() {
        lastNotified.clear();
        lastAutomation.clear();
        sessionStartedAt = java.lang.System.currentTimeMillis();
        sessionDeaths = 0;

        AutomationManager manager = AutomationManager.get();
        if (manager != null) {
            // A single subscription for every automation event keeps the routing in one place instead of
            // each producer needing to know which webhook settings apply.
            automationListener = this::onAutomationEvent;
            manager.bus().subscribeAll(automationListener);
        }
    }

    @Override
    public void onDeactivate() {
        AutomationManager manager = AutomationManager.get();
        if (manager != null && automationListener != null) {
            manager.bus().unsubscribeAll(automationListener);
            automationListener = null;
        }
    }

    /**
     * Turns an internal automation event into a webhook, subject to per-category filters and a per-type
     * cooldown so a flapping condition cannot flood the channel.
     */
    private void onAutomationEvent(AutomationEvent event) {
        if (!isCategoryEnabled(event.type())) return;
        if (isOnCooldown(event.type())) return;

        WebhookManager.Embed embed = baseEmbed(titleFor(event.type()), colorFor(event.type()));
        embed.description(describe(event));

        for (var entry : event.data().entrySet()) {
            embed.field(prettify(entry.getKey()), entry.getValue(), true);
        }

        if (includeCoordinates.get()) embed.field("Our position", ServerUtils.coordinates());

        send(embed);
    }

    private boolean isCategoryEnabled(AutomationEvent.Type type) {
        return switch (type) {
            case STASH_DISCOVERED, STASH_UPGRADED -> notifyStash.get();
            case STRUCTURE_FOUND, NEW_CHUNK_CLUSTER -> notifyStructure.get();
            case ROUTE_STARTED, ROUTE_INTERRUPTED, ROUTE_RESUMED, ROUTE_COMPLETED,
                 TARGET_REACHED, FRONTIER_PUSHED, EXPLORATION_STALLED, MILESTONE -> notifyExploration.get();
            case ELYTRA_DURABILITY_LOW, ELYTRA_REPLACED, ELYTRA_MISSING, ELYTRA_BROKEN,
                 EQUIPMENT_DURABILITY_LOW -> notifyEquipment.get();
            case MENDING_REPAIR_NEEDED, MENDING_REPAIR_DONE -> notifyMending.get();
            case ENDER_CHEST_FOUND, ENDER_CHEST_OPENED, ENDER_CHEST_CLOSED, INVENTORY_ORGANISED, INVENTORY_FULL -> notifyEnderChest.get();
            case HEALTH_CRITICAL, HUNGER_CRITICAL, DANGER_DETECTED, FLIGHT_INTERRUPTED, LANDED_UNEXPECTEDLY,
                 FLIGHT_DEPLOY_FAILED, DIMENSION_CHANGED -> notifyEmergency.get();
            case PLAYER_SIGHTED, PLAYER_LOGOUT_SPOT -> notifyPlayerSightings.get();
            case STORAGE_SORTED, ITEMS_DEPOSITED, ITEMS_WITHDRAWN, ITEMS_RESTOCKED, CONTAINER_REGISTERED,
                 CONTAINER_OP_FAILED, SHULKER_FILLED, SHULKER_EMPTIED, WORKFLOW_STARTED, WORKFLOW_STEP_DONE,
                 WORKFLOW_COMPLETED, WORKFLOW_FAILED -> notifyStorage.get();
            case TASK_QUEUED, TASK_STARTED, TASK_COMPLETED, TASK_PARTIAL, TASK_FAILED, TASK_CANCELLED,
                 KIT_CLAIM_ATTEMPTED, KIT_CLAIM_SUCCEEDED, KIT_CLAIM_FAILED, KIT_ON_COOLDOWN,
                 KIT_AVAILABLE -> notifyTasks.get();
            case CHAT_COMMAND, CHAT_COMMAND_DENIED -> notifyChatCommands.get();
            case SYSTEM_ERROR -> notifyErrors.get();
            default -> false;
        };
    }

    private boolean isOnCooldown(AutomationEvent.Type type) {
        if (automationCooldown.get() <= 0) return false;

        long now = java.lang.System.currentTimeMillis();
        Long previous = lastAutomation.get(type);
        if (previous != null && now - previous < automationCooldown.get() * 1000L) return true;

        lastAutomation.put(type, now);
        return false;
    }

    private String titleFor(AutomationEvent.Type type) {
        return switch (type) {
            case STASH_DISCOVERED -> "Stash discovered";
            case STASH_UPGRADED -> "Stash grew";
            case STRUCTURE_FOUND -> "Interesting structure";
            case NEW_CHUNK_CLUSTER -> "New chunk cluster";
            case ROUTE_STARTED -> "Route started";
            case ROUTE_INTERRUPTED -> "Route interrupted";
            case ROUTE_RESUMED -> "Route resumed";
            case ROUTE_COMPLETED -> "Route completed";
            case TARGET_REACHED -> "Target reached";
            case FRONTIER_PUSHED -> "Frontier pushed";
            case EXPLORATION_STALLED -> "Exploration stalled";
            case MILESTONE -> "Milestone";
            case ELYTRA_DURABILITY_LOW -> "Elytra durability low";
            case ELYTRA_REPLACED -> "Elytra replaced";
            case ELYTRA_MISSING -> "No elytra";
            case ELYTRA_BROKEN -> "Elytra broke";
            case EQUIPMENT_DURABILITY_LOW -> "Equipment durability low";
            case MENDING_REPAIR_NEEDED -> "Mending needed";
            case MENDING_REPAIR_DONE -> "Mending done";
            case INVENTORY_FULL -> "Inventory full";
            case ENDER_CHEST_FOUND -> "Ender chest found";
            case ENDER_CHEST_OPENED -> "Ender chest opened";
            case ENDER_CHEST_CLOSED -> "Ender chest closed";
            case INVENTORY_ORGANISED -> "Inventory organised";
            case HEALTH_CRITICAL -> "Health critical";
            case HUNGER_CRITICAL -> "Hunger critical";
            case DANGER_DETECTED -> "Danger detected";
            case FLIGHT_INTERRUPTED -> "Flight interrupted";
            case LANDED_UNEXPECTEDLY -> "Landed unexpectedly";
            case FLIGHT_DEPLOY_FAILED -> "Flight deployment failed";
            case DIMENSION_CHANGED -> "Dimension changed";
            case PLAYER_SIGHTED -> "Player sighted";
            case PLAYER_LOGOUT_SPOT -> "Logout spot recorded";
            case SYSTEM_ERROR -> "System error";
            case STORAGE_SORTED -> "Storage sorted";
            case ITEMS_DEPOSITED -> "Items deposited";
            case ITEMS_WITHDRAWN -> "Items withdrawn";
            case ITEMS_RESTOCKED -> "Restocked";
            case CONTAINER_REGISTERED -> "Container registered";
            case CONTAINER_OP_FAILED -> "Storage operation failed";
            case SHULKER_FILLED -> "Shulker filled";
            case SHULKER_EMPTIED -> "Shulker emptied";
            case WORKFLOW_STARTED -> "Workflow started";
            case WORKFLOW_STEP_DONE -> "Workflow step done";
            case WORKFLOW_COMPLETED -> "Workflow completed";
            case WORKFLOW_FAILED -> "Workflow failed";
            case TASK_QUEUED -> "Task queued";
            case TASK_STARTED -> "Task started";
            case TASK_COMPLETED -> "Task completed";
            case TASK_PARTIAL -> "Task partly completed";
            case TASK_FAILED -> "Task failed";
            case TASK_CANCELLED -> "Task cancelled";
            case KIT_CLAIM_ATTEMPTED -> "Claiming kit";
            case KIT_CLAIM_SUCCEEDED -> "Kit claimed";
            case KIT_CLAIM_FAILED -> "Kit refused";
            case KIT_ON_COOLDOWN -> "Kit on cooldown";
            case KIT_AVAILABLE -> "Kit available";
            case CHAT_COMMAND -> "Chat command";
            case CHAT_COMMAND_DENIED -> "Chat command refused";
            default -> prettify(type.name());
        };
    }

    private int colorFor(AutomationEvent.Type type) {
        return switch (type) {
            case STASH_DISCOVERED -> 0xFFD700;
            case STASH_UPGRADED, STRUCTURE_FOUND, NEW_CHUNK_CLUSTER -> 0xFFAA00;
            case ROUTE_STARTED, ROUTE_RESUMED, TARGET_REACHED -> 0x00C8FF;
            case ROUTE_COMPLETED, MENDING_REPAIR_DONE, INVENTORY_ORGANISED -> 0x00FF88;
            case ROUTE_INTERRUPTED, EXPLORATION_STALLED, MENDING_REPAIR_NEEDED, ELYTRA_DURABILITY_LOW,
                 ELYTRA_MISSING, EQUIPMENT_DURABILITY_LOW, INVENTORY_FULL -> 0xFFAA00;
            case ELYTRA_REPLACED, ENDER_CHEST_FOUND, ENDER_CHEST_OPENED -> 0x00C8FF;
            case PLAYER_SIGHTED, PLAYER_LOGOUT_SPOT -> 0xFF0080;
            case STORAGE_SORTED, ITEMS_DEPOSITED, ITEMS_WITHDRAWN, ITEMS_RESTOCKED, SHULKER_FILLED,
                 SHULKER_EMPTIED, WORKFLOW_STARTED, WORKFLOW_STEP_DONE, WORKFLOW_COMPLETED -> 0x00FF88;
            case CONTAINER_REGISTERED -> 0x00C8FF;
            case CONTAINER_OP_FAILED, WORKFLOW_FAILED, TASK_FAILED, KIT_CLAIM_FAILED -> 0xFF5555;
            case TASK_QUEUED, TASK_STARTED, KIT_CLAIM_ATTEMPTED, KIT_AVAILABLE -> 0x00C8FF;
            case TASK_COMPLETED, KIT_CLAIM_SUCCEEDED -> 0x00FF88;
            case TASK_PARTIAL, TASK_CANCELLED, KIT_ON_COOLDOWN, CHAT_COMMAND_DENIED -> 0xFFAA00;
            case CHAT_COMMAND -> 0xAAAAAA;
            case ELYTRA_BROKEN, HEALTH_CRITICAL, HUNGER_CRITICAL, DANGER_DETECTED, FLIGHT_INTERRUPTED,
                 LANDED_UNEXPECTEDLY, FLIGHT_DEPLOY_FAILED, SYSTEM_ERROR -> 0xFF5555;
            default -> 0xAAAAAA;
        };
    }

    private String describe(AutomationEvent event) {
        return switch (event.type()) {
            case ROUTE_INTERRUPTED -> "The route was interrupted and its progress was saved.";
            case ROUTE_RESUMED -> "The route resumed from the saved target.";
            case EXPLORATION_STALLED -> "Nothing new found for %s minute(s), expanding the sweep.".formatted(event.get("minutes"));
            case DANGER_DETECTED -> "A dangerous condition was detected.";
            case PLAYER_SIGHTED -> "A player was sighted at %s, %s, %s.".formatted(
                event.get("x"), event.get("y"), event.get("z"));
            case PLAYER_LOGOUT_SPOT -> "%s logged out at %s.".formatted(event.get("player"), event.get("logout"));
            case STASH_DISCOVERED, STASH_UPGRADED -> "%s (%s)".formatted(
                event.get("classification"), "score " + event.get("score"));
            case STORAGE_SORTED -> "%s item(s) sorted in %s.".formatted(event.get("moves"), event.get("scope"));
            case ITEMS_DEPOSITED -> "%s stack(s) deposited into %s.".formatted(
                event.get("stacks"), event.get("container"));
            case ITEMS_WITHDRAWN -> "%s stack(s) withdrawn from %s.".formatted(
                event.get("stacks"), event.get("container"));
            case ITEMS_RESTOCKED -> "Restocked %s item(s) from storage.".formatted(event.get("items"));
            case CONTAINER_REGISTERED -> "Registered %s at %s.".formatted(event.get("type"), event.get("pos"));
            case CONTAINER_OP_FAILED -> "A storage operation failed: %s.".formatted(event.get("reason"));
            case SHULKER_FILLED -> "Filled a shulker box with %s stack(s).".formatted(event.get("stacks"));
            case SHULKER_EMPTIED -> "Emptied a shulker box (%s stack(s)).".formatted(event.get("stacks"));
            case WORKFLOW_STARTED -> "Workflow '%s' started.".formatted(event.get("workflow"));
            case WORKFLOW_STEP_DONE -> "Workflow '%s' step: %s.".formatted(event.get("workflow"), event.get("step"));
            case WORKFLOW_COMPLETED -> "Workflow '%s' completed.".formatted(event.get("workflow"));
            case WORKFLOW_FAILED -> "Workflow '%s' failed at %s.".formatted(event.get("workflow"), event.get("step"));
            case TASK_QUEUED -> "Task '%s' queued.".formatted(event.get("task"));
            case TASK_COMPLETED -> "Task '%s' completed.".formatted(event.get("task"));
            case TASK_PARTIAL -> "Task '%s' only partly completed.".formatted(event.get("task"));
            case TASK_FAILED -> "Task '%s' failed: %s.".formatted(event.get("task"), event.get("reason"));
            case TASK_CANCELLED -> "Task '%s' was cancelled.".formatted(event.get("task"));
            case KIT_CLAIM_SUCCEEDED -> "Kit '%s' claimed.".formatted(event.get("kit"));
            case KIT_CLAIM_FAILED -> "Kit '%s' was refused.".formatted(event.get("kit"));
            case KIT_ON_COOLDOWN -> "Kit '%s' is on cooldown.".formatted(event.get("kit"));
            case CHAT_COMMAND_DENIED -> "Chat command '%s' was refused.".formatted(event.get("command"));
            case CHAT_COMMAND -> "Chat command '%s' ran.".formatted(event.get("command"));
            default -> "";
        };
    }

    private static String prettify(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        StringBuilder builder = new StringBuilder(raw.length());
        boolean upper = true;

        for (char c : raw.toCharArray()) {
            if (c == '-' || c == '_') {
                builder.append(' ');
                upper = true;
                continue;
            }

            builder.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }

        return builder.toString();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        sessionStartedAt = java.lang.System.currentTimeMillis();

        if (!notifyJoin.get()) return;

        WebhookManager.Embed embed = baseEmbed("Joined server", 0x00FF88)
            .field("Player", ServerUtils.playerName());

        if (includeCoordinates.get()) embed.field("Position", ServerUtils.coordinates());
        embed.field("Dimension", ServerUtils.dimension());

        send(embed);

        if (chatFeedback.get()) info("Webhook session started.");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!notifyLeave.get()) return;

        long seconds = (java.lang.System.currentTimeMillis() - sessionStartedAt) / 1000L;

        WebhookManager.Embed embed = baseEmbed("Disconnected", 0xFF5555)
            .field("Player", ServerUtils.playerName())
            .field("Session length", formatDuration(seconds));

        if (includeCoordinates.get()) embed.field("Last position", ServerUtils.coordinates());

        send(embed);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            if (!notifyPlayerJoin.get()) return;
            if (!packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;

            for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
                if (entry.profile() == null) continue;

                String name = entry.profile().name();
                if (mc.player != null && name.equals(mc.player.getGameProfile().name())) continue;

                UUID id = entry.profileId();
                if (onCooldown(id)) continue;

                PlayerTracker tracker = PlayerTracker.get();
                if (tracker != null && mc.player != null) {
                    tracker.track(id, name, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
                    tracker.markLoggedIn(id);
                }

                WebhookManager.Embed embed = baseEmbed("Player joined", 0x00C8FF)
                    .field("Player", name)
                    .field("Latency", entry.latency() + " ms");

                if (includeCoordinates.get()) embed.field("Our position", ServerUtils.coordinates());

                send(embed);
            }

            return;
        }

        if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            if (!notifyPlayerLeave.get()) return;

            for (UUID id : packet.profileIds()) {
                if (onCooldown(id)) continue;

                PlayerTracker tracker = PlayerTracker.get();
                PlayerTracker.Entry entry = tracker == null ? null : tracker.get(id);

                if (tracker != null && mc.player != null) {
                    tracker.markLoggedOut(id, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
                }

                // The logger module owns the logout messaging, so let it build the notification.
                PlayerLogger logger = Modules.get().get(PlayerLogger.class);
                if (logger != null && logger.isActive() && entry != null) {
                    logger.recordLogout(entry, new BlockPos(
                        entry.logoutX, entry.logoutY, entry.logoutZ));
                }

                String name = entry != null ? entry.name : id.toString();

                WebhookManager.Embed embed = baseEmbed("Player left", 0xFFAA00)
                    .field("Player", name);

                if (entry != null) {
                    embed.field("Last seen", entry.lastCoords());
                    embed.field("First seen", entry.firstCoords());
                }

                if (includeCoordinates.get()) embed.field("Our position", ServerUtils.coordinates());

                send(embed);
            }
        }
    }

    private boolean onCooldown(UUID id) {
        if (messageCooldown.get() <= 0) return false;

        long now = java.lang.System.currentTimeMillis();
        Long previous = lastNotified.get(id);
        if (previous != null && now - previous < messageCooldown.get() * 1000L) return true;

        lastNotified.put(id, now);
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!notifyPlayerNearby.get() || mc.player == null || mc.world == null) return;

        double radiusSq = (double) nearbyRadius.get() * nearbyRadius.get();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (mc.player.squaredDistanceTo(player) > radiusSq) continue;

            UUID id = player.getUuid();
            if (onCooldown(id)) continue;

            int distance = (int) mc.player.distanceTo(player);

            send(baseEmbed("Player nearby", 0xFFAA00)
                .field("Player", player.getName().getString())
                .field("Distance", distance + " blocks")
                .field("Their position", "%d, %d, %d".formatted(
                    (int) player.getX(), (int) player.getY(), (int) player.getZ()))
                .field("Our position", ServerUtils.coordinates()));

            if (chatFeedback.get()) warning("%s is %d blocks away.", player.getName().getString(), distance);
        }
    }

    /** Called by the other modules when they want to reuse this module's embed formatting. */
    public WebhookManager.Embed baseEmbed(String heading, int color) {
        WebhookManager.Embed embed = new WebhookManager.Embed()
            .title(title.get().isBlank() ? heading : title.get() + " " + heading)
            .color(color)
            .footer("Base Hunting Client");

        if (includeServer.get()) embed.field("Server", ServerUtils.serverAddress());

        return embed;
    }

    private void send(WebhookManager.Embed embed) {
        if (WebhookSettings.globallyMuted()) return;
        webhook.send(embed);
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;

        if (hours > 0) return "%dh %dm".formatted(hours, minutes);
        if (minutes > 0) return "%dm %ds".formatted(minutes, seconds % 60);
        return seconds + "s";
    }

    public int getTrackedPlayerCount() {
        PlayerTracker tracker = PlayerTracker.get();
        return tracker == null ? 0 : tracker.forCurrentServer().size();
    }

    public int getStashCount() {
        StashManager manager = StashManager.get();
        return manager == null ? 0 : manager.forCurrentServer().size();
    }
}
