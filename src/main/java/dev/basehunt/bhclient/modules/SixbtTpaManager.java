package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.sixbt.SixbtChat;
import dev.basehunt.bhclient.core.sixbt.TpaPolicy;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Map;

/**
 * Drives 6b6t teleport requests: sending {@code /tpa}, cancelling it, and answering incoming requests.
 *
 * <p>On 6b6t a teleport is a two-step handshake: you ask with {@code /tpa <player>}, the other player accepts
 * with {@code /tpy} or refuses with {@code /tpn}, and you can withdraw your own request with {@code /tpcancel}.
 * This module wraps all four, plus an optional auto-answer for incoming requests that is restricted to an
 * allow-list so the bot cannot be yanked across the map by a stranger.
 *
 * <p>It is separate from the kit bot's own teleporting on purpose: the kit bot needs to teleport to a specific
 * requester as part of a delivery, while this is the general-purpose player-facing control. Both go through
 * the same {@link TpaPolicy}, so the accept rules cannot drift apart.
 */
public class SixbtTpaManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoAnswer = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-answer")
        .description("Answer incoming /tpa requests automatically.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> acceptAnyone = sgGeneral.add(new BoolSetting.Builder()
        .name("accept-anyone")
        .description("Accept every request, not only the allow-list. Only safe in a secure spot.")
        .defaultValue(false)
        .visible(autoAnswer::get)
        .build()
    );

    private final Setting<List<String>> allowList = sgGeneral.add(new StringListSetting.Builder()
        .name("allow-list")
        .description("Players whose requests are accepted.")
        .defaultValue(List.of())
        .visible(autoAnswer::get)
        .build()
    );

    private final Setting<List<String>> blockList = sgGeneral.add(new StringListSetting.Builder()
        .name("block-list")
        .description("Players whose requests are refused outright.")
        .defaultValue(List.of())
        .visible(autoAnswer::get)
        .build()
    );

    private final Setting<Integer> requestTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("request-timeout")
        .description("Seconds before an unanswered request of ours is cancelled with /tpcancel.")
        .defaultValue(30)
        .min(5)
        .sliderRange(5, 120)
        .build()
    );

    private TpaPolicy policy = TpaPolicy.denyAll();
    private String pendingRequest = "";
    private int pendingTicks;

    public SixbtTpaManager() {
        super(BaseHuntingAddon.CATEGORY, "6b6t-tpa",
            "Sends, cancels and auto-answers 6b6t teleport requests.");
    }

    @Override
    public void onActivate() {
        rebuildPolicy();
        pendingRequest = "";
        pendingTicks = 0;
    }

    private void rebuildPolicy() {
        policy = new TpaPolicy(allowList.get(), blockList.get(), acceptAnyone.get());
    }

    /** Sends a teleport request to a player. */
    public String request(String player) {
        if (player == null || player.isBlank()) return "usage: tpa <player>";
        if (!dev.basehunt.bhclient.core.sixbt.SixbtCommand.isSixbtServer(ServerUtils.serverAddress())) {
            warning("This does not look like a 6b6t server.");
        }

        if (mc.player == null || mc.player.networkHandler == null) return "not connected";
        sendLine("/tpa " + player);
        pendingRequest = player;
        pendingTicks = requestTimeout.get() * 20;

        publish(AutomationEvent.Type.SIXBT_TPA_SENT, Map.of("player", player));
        info("Sent a teleport request to %s.", player);
        return "requested " + player;
    }

    /** Cancels the request we sent. */
    public String cancel() {
        sendLine("/tpcancel");
        String who = pendingRequest;
        pendingRequest = "";
        pendingTicks = 0;
        info("Cancelled the teleport request%s.", who.isEmpty() ? "" : " to " + who);
        return "cancelled";
    }

    /** Accepts a request from a player. */
    public String accept(String player) {
        if (player == null || player.isBlank()) return "usage: tpy <player>";
        sendLine("/tpy " + player);
        publish(AutomationEvent.Type.SIXBT_TPA_ACCEPTED, Map.of("player", player));
        info("Accepted the teleport request from %s.", player);
        return "accepted " + player;
    }

    /** Refuses a request from a player. */
    public String decline(String player) {
        if (player == null || player.isBlank()) return "usage: tpn <player>";
        sendLine("/tpn " + player);
        publish(AutomationEvent.Type.SIXBT_TPA_DECLINED, Map.of("player", player));
        info("Refused the teleport request from %s.", player);
        return "declined " + player;
    }

    @Override
    public void onDeactivate() {
        pendingRequest = "";
        pendingTicks = 0;
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        if (pendingRequest.isEmpty()) return;
        if (--pendingTicks > 0) return;
        // Our request went unanswered; withdraw it rather than leaving a stale countdown open.
        sendLine("/tpcancel");
        warning("No answer from %s; cancelled the request.", pendingRequest);
        pendingRequest = "";
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        String lower = text.toLowerCase(java.util.Locale.ROOT);

        if (!pendingRequest.isEmpty()) {
            if (lower.contains("accepted") && lower.contains(pendingRequest.toLowerCase(java.util.Locale.ROOT))) {
                publish(AutomationEvent.Type.SIXBT_TPA_ACCEPTED, Map.of("player", pendingRequest));
                pendingRequest = "";
            } else if (lower.contains("denied") || lower.contains("declined") || lower.contains("refused")) {
                publish(AutomationEvent.Type.SIXBT_TPA_DECLINED, Map.of("player", pendingRequest));
                pendingRequest = "";
            }
        }

        if (!autoAnswer.get()) return;
        SixbtChat.findTpaRequest(text).ifPresent(player -> {
            rebuildPolicy();
            TpaPolicy.Decision decision = policy.decide(player);
            if (decision == TpaPolicy.Decision.ACCEPT) {
                sendLine("/tpy " + player);
                publish(AutomationEvent.Type.SIXBT_TPA_ACCEPTED, Map.of("player", player));
                info("Auto-accepted a teleport request from %s.", player);
            } else if (decision == TpaPolicy.Decision.DECLINE) {
                sendLine("/tpn " + player);
                publish(AutomationEvent.Type.SIXBT_TPA_DECLINED, Map.of("player", player));
            }
        });
    }

    private void sendLine(String line) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(line);
        }
    }

    public String statusLine() {
        if (pendingRequest.isEmpty()) {
            return autoAnswer.get() ? "auto-answer on (%d allowed)".formatted(policy.allowList().size())
                : "auto-answer off";
        }
        return "waiting on %s (%ds)".formatted(pendingRequest, pendingTicks / 20);
    }

    public List<String> describe() {
        return List.of(
            "auto-answer: " + autoAnswer.get(),
            "accept-anyone: " + acceptAnyone.get(),
            "allow-list: " + (policy.allowList().isEmpty() ? "(none)" : String.join(", ", policy.allowList())),
            "block-list: " + (policy.blockList().isEmpty() ? "(none)" : String.join(", ", policy.blockList())),
            "pending: " + (pendingRequest.isEmpty() ? "(none)" : pendingRequest)
        );
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
