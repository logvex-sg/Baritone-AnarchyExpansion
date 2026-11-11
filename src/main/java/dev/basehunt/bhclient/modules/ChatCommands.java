package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.chat.ChatCommand;
import dev.basehunt.bhclient.core.chat.ChatCommandRegistry;
import dev.basehunt.bhclient.core.chat.ChatContext;
import dev.basehunt.bhclient.core.chat.ChatPermissions;
import dev.basehunt.bhclient.core.chat.ChatRouter;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.PlayerFacts;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs addon commands typed into Minecraft chat behind a configurable prefix.
 *
 * <p>The important safety property is where the line came from. Only a message the local player typed can be a
 * command; a message received from another player is data and is never parsed, and the addon's own output is
 * ignored. On top of that, {@link ChatPermissions} refuses any destructive command unless the operator has
 * allowed it, so a mistyped {@code !withdraw all} cannot empty a chest by accident.
 *
 * <p>The commands here reuse the same managers the GUI and the {@code /bh} command use, so the three entry
 * points cannot drift apart. A command that starts a long operation submits a task to the
 * {@link AutomationEngine} rather than doing the work inline.
 */
public class ChatCommands extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPermissions = settings.createGroup("Permissions");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Reply in chat when a command runs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> intercept = sgGeneral.add(new BoolSetting.Builder()
        .name("intercept-chat")
        .description("Cancel the message so the server never sees the prefix command.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> prefixes = sgGeneral.add(new StringListSetting.Builder()
        .name("prefixes")
        .description("Chat prefixes that start a command, e.g. ! and .bh.")
        .defaultValue(List.of("!", ".bh"))
        .build()
    );

    private final Setting<List<String>> allowedCommands = sgPermissions.add(new StringListSetting.Builder()
        .name("allowed-destructive")
        .description("Command names allowed to move the player or change settings. Use * to allow all.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Boolean> allowAllDestructive = sgPermissions.add(new BoolSetting.Builder()
        .name("allow-all-destructive")
        .description("Allow every command, including movement and control. Use with care.")
        .defaultValue(false)
        .build()
    );

    private final ChatCommandRegistry registry = new ChatCommandRegistry();

    public ChatCommands() {
        super(BaseHuntingAddon.CATEGORY, "chat-commands",
            "Runs addon commands typed into chat behind a prefix, with per-command permissions.");
    }

    @Override
    public void onActivate() {
        buildRegistry();
        applyPermissions();
    }

    /** Rebuilds the command table from the current settings. */
    private void buildRegistry() {
        registry.setPermissions(ChatPermissions.of(java.util.Set.copyOf(allowedCommands.get()),
            allowAllDestructive.get()));

        registry.register(new ChatCommand("help", "help", "list the available chat commands",
            ChatPermissions.Level.READ_ONLY), this::help);
        registry.register(new ChatCommand("status", "status", "show the automation status",
            ChatPermissions.Level.READ_ONLY), this::status);
        registry.register(new ChatCommand("whoami", "whoami", "show your name, position and server",
            ChatPermissions.Level.READ_ONLY), this::whoami);
        registry.register(new ChatCommand("tasks", "tasks", "list queued and recent automation tasks",
            ChatPermissions.Level.READ_ONLY), this::tasks);
        registry.register(new ChatCommand("elytra", "elytra", "show the elytra status",
            ChatPermissions.Level.READ_ONLY), this::elytra);
        registry.register(new ChatCommand("kits", "kits", "list the known kits and cooldowns",
            ChatPermissions.Level.READ_ONLY, new String[] {"kit"}), this::kits);
        registry.register(new ChatCommand("stash", "stash", "count the recorded stashes",
            ChatPermissions.Level.READ_ONLY), this::stash);
        registry.register(new ChatCommand("storage", "storage", "show the registered storage",
            ChatPermissions.Level.READ_ONLY), this::storage);
        registry.register(new ChatCommand("withdraw", "withdraw <item> [amount]",
            "pull items out of the open container", ChatPermissions.Level.MOVEMENT), this::withdraw);
        registry.register(new ChatCommand("deposit", "deposit <item> [amount]",
            "put items into the open container", ChatPermissions.Level.MOVEMENT), this::deposit);
        registry.register(new ChatCommand("kit-claim", "kit-claim <name>", "claim a server kit",
            ChatPermissions.Level.CONTROL, new String[] {"claim"}), this::kitClaim);
        registry.register(new ChatCommand("cancel", "cancel", "cancel the active task",
            ChatPermissions.Level.CONTROL), this::cancel);
    }

    /** Re-reads the permission settings, so a change applies without a re-enable. */
    public void applyPermissions() {
        registry.setPermissions(ChatPermissions.of(java.util.Set.copyOf(allowedCommands.get()),
            allowAllDestructive.get()));
    }

    public ChatCommandRegistry registry() {
        return registry;
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (mc.player == null) return;

        ChatRouter.Parsed parsed = ChatRouter.parse(event.message, prefixes.get(), ChatRouter.Origin.LOCAL_PLAYER);
        if (parsed == null) return;

        // Run the command locally. When intercepting, cancel the event so the raw line never reaches the server;
        // when not, the command also goes through as ordinary chat, which is what a server that expects its own
        // prefixed commands wants.
        if (intercept.get()) event.cancel();
        run(parsed);
    }

    /** Parses and runs a line as if the local player had typed it. Public so the terminal can reuse it. */
    public String runLine(String line) {
        ChatRouter.Parsed parsed = ChatRouter.parse(line, prefixes.get(), ChatRouter.Origin.LOCAL_PLAYER);
        if (parsed == null) return "not a command";
        return run(parsed);
    }

    private String run(ChatRouter.Parsed parsed) {
        ChatContext context = new ChatContext(parsed, parsed.prefix(),
            message -> {
                if (chatFeedback.get()) info("%s", message);
            },
            message -> {
                if (chatFeedback.get()) error("%s", message);
            });

        String result = registry.dispatch(context);

        publish(result.equals("denied") ? AutomationEvent.Type.CHAT_COMMAND_DENIED
            : AutomationEvent.Type.CHAT_COMMAND, Map.of("command", parsed.name(), "result", result));
        return result;
    }

    // Handlers

    private void help(ChatContext context) {
        for (String line : registry.help(context.prefix())) context.info("%s", line);
    }

    private void status(ChatContext context) {
        AutomationEngine engine = AutomationEngine.get();
        context.info("Automation: %s", engine == null ? "unavailable" : engine.statusLine());
        context.info("Elytra: %s", ElytraManager.status());
        context.info("Kits: %s", kitBotStatus());
        PlayerFacts.Snapshot facts = PlayerFacts.snapshot();
        context.info("Health %s, hunger %s, free slots %s".formatted(
            factText(facts, "health"), factText(facts, "hunger"), factText(facts, "free_slots")));
    }

    private void whoami(ChatContext context) {
        context.info("%s", ServerUtils.position());
    }

    private void tasks(ChatContext context) {
        AutomationEngine engine = AutomationEngine.get();
        if (engine == null) {
            context.error("the automation engine is not running");
            return;
        }
        if (engine.queue().active() != null) context.info("Active: %s", engine.queue().active().describe());
        List<Task> queued = engine.queue().queued();
        context.info("Queued: %d", queued.size());
        for (Task task : queued) context.info("  %s", task.describe());
        for (Task task : engine.queue().history().subList(0, Math.min(5, engine.queue().history().size()))) {
            context.info("  done: %s", task.describe());
        }
    }

    private void elytra(ChatContext context) {
        context.info("%s", ElytraManager.status());
        context.info("Spares: %d", ElytraManager.spareCount());
    }

    private void kits(ChatContext context) {
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) {
            context.error("the kit bot is not loaded");
            return;
        }
        for (String line : bot.describe()) context.info("%s", line);
    }

    private void stash(ChatContext context) {
        var stashes = dev.basehunt.bhclient.systems.StashManager.get();
        context.info("Stashes here: %d", stashes == null ? 0 : stashes.forCurrentServer().size());
    }

    private void storage(ChatContext context) {
        StorageRegistry registryStore = StorageRegistry.get();
        int count = registryStore == null ? 0 : registryStore.forCurrentServer().size();
        context.info("Registered containers here: %d", count);
    }

    private void withdraw(ChatContext context) {
        if (context.argCount() < 1) {
            context.error("usage: %swithdraw <item> [amount]", context.prefix());
            return;
        }
        String filter = context.arg(0);
        int amount = context.intArg(1, -1);
        startStorageTask(Task.Category.WITHDRAW, filter, amount);
        context.info("Withdrawing %s%s.", filter, amount > 0 ? " x" + amount : "");
    }

    private void deposit(ChatContext context) {
        if (context.argCount() < 1) {
            context.error("usage: %sdeposit <item> [amount]", context.prefix());
            return;
        }
        String filter = context.arg(0);
        int amount = context.intArg(1, -1);
        startStorageTask(Task.Category.DEPOSIT, filter, amount);
        context.info("Depositing %s%s.", filter, amount > 0 ? " x" + amount : "");
    }

    private void kitClaim(ChatContext context) {
        if (context.argCount() < 1) {
            context.error("usage: %skit-claim <name>", context.prefix());
            return;
        }
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) {
            context.error("the kit bot is not loaded");
            return;
        }
        context.info("%s", bot.claim(context.arg(0)));
    }

    private void cancel(ChatContext context) {
        AutomationEngine engine = AutomationEngine.get();
        if (engine == null) {
            context.error("the automation engine is not running");
            return;
        }
        int cancelled = engine.cancelAll("cancelled from chat");
        context.info("Cancelled %d task(s).", cancelled);
    }

    private void startStorageTask(Task.Category category, String filter, int amount) {
        AutomationEngine engine = AutomationEngine.get();
        if (engine == null) return;

        Task task = category == Task.Category.WITHDRAW
            ? dev.basehunt.bhclient.systems.StorageAutomation.withdrawTask(0, filter, amount, -1, true)
            : dev.basehunt.bhclient.systems.StorageAutomation.depositTask(0, filter, amount, -1, true);

        // The engine assigns the id; the convenience builders use 0 as a placeholder.
        Task submitted = engine.submit(task);
        if (submitted == null) publish(AutomationEvent.Type.SYSTEM_ERROR,
            Map.of("reason", "a matching task is already queued"));
    }

    private String kitBotStatus() {
        KitBot bot = Modules.get().get(KitBot.class);
        return bot == null || !bot.isActive() ? "inactive" : bot.statusLine();
    }

    private static String factText(PlayerFacts.Snapshot facts, String name) {
        Double value = facts.fact(name);
        if (value == null) return "-";
        return value == Math.floor(value) ? String.valueOf(value.longValue()) : "%.1f".formatted(value);
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
