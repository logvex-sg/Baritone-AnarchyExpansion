package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.chat.ChatPermissions;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.core.terminal.CommandAliases;
import dev.basehunt.bhclient.core.terminal.CommandHistory;
import dev.basehunt.bhclient.core.terminal.CommandLine;
import dev.basehunt.bhclient.core.terminal.CommandRegistry;
import dev.basehunt.bhclient.core.terminal.MacroStore;
import dev.basehunt.bhclient.core.terminal.TerminalBuffer;
import dev.basehunt.bhclient.core.terminal.TerminalCommand;
import dev.basehunt.bhclient.core.terminal.TerminalContext;
import dev.basehunt.bhclient.gui.BaseHuntingScreen;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.DatabaseIO;
import dev.basehunt.bhclient.systems.EnderChestStore;
import dev.basehunt.bhclient.systems.ExplorationStore;
import dev.basehunt.bhclient.systems.NavigationManager;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The in-game command terminal: a real command interface over the addon's managers.
 *
 * <p>This is the runtime half of the terminal. It builds a {@link CommandRegistry} whose handlers call the
 * same public methods the GUI and {@code /bh} use, so the three entry points cannot drift apart, and it is
 * the only place that touches Minecraft. The registry, the parser, the history and the alias table are pure
 * and covered by the headless suite.
 *
 * <p>Everything the terminal can do is an addon operation. There is deliberately no shell, no filesystem and
 * no arbitrary code execution: the "run" of {@code chat run} feeds the chat command registry, which only
 * knows addon commands. A command that starts long work submits a task to the {@link AutomationEngine} and
 * reports the submission, rather than blocking the client thread.
 *
 * <p>The terminal is local-only. Its input comes from the GUI text box or the {@code /bh terminal} command,
 * never from a chat message another player sent, so it can allow the destructive commands the chat prefix
 * system refuses by default.
 */
public class CommandTerminal extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> aliasLines = sgGeneral.add(new StringListSetting.Builder()
        .name("aliases")
        .description("Shortcuts as name=expansion, e.g. s=status or w=withdraw.")
        .defaultValue(List.of("s=status", "t=tasks", "h=help", "w=withdraw", "d=deposit", "k=kits"))
        .build()
    );

    private final Setting<Boolean> allowDestructive = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-destructive")
        .description("Allow movement and control commands typed into the terminal. Local input only.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTimestamps = sgGeneral.add(new BoolSetting.Builder()
        .name("timestamps")
        .description("Prefix each output line with its time.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> macroLines = sgGeneral.add(new StringListSetting.Builder()
        .name("macros")
        .description("Named command sequences as name = step ; step, run with 'macro run <name>'.")
        .defaultValue(List.of("preflight = elytra status ; storage restock ; survival status"))
        .build()
    );

    private final CommandRegistry registry = new CommandRegistry();
    private final TerminalBuffer buffer = new TerminalBuffer();
    private final CommandHistory history = new CommandHistory();
    private final CommandAliases aliases = new CommandAliases();
    private final MacroStore macros = new MacroStore();
    private int macroDepth = 0;

    public CommandTerminal() {
        super(BaseHuntingAddon.CATEGORY, "command-terminal",
            "An in-game command terminal that drives the addon's automation, storage and navigation.");
    }

    @Override
    public void onActivate() {
        rebuild();
    }

    /** Rebuilds the alias table, macro table and command registry from the current settings. */
    public void rebuild() {
        aliases.clear();
        for (String line : aliasLines.get()) {
            if (line == null) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            aliases.put(line.substring(0, eq), line.substring(eq + 1));
        }
        macros.clear();
        for (String line : macroLines.get()) macros.addLine(line);
        registry.setPermissions(allowDestructive.get()
            ? ChatPermissions.of(java.util.Set.of("*"), true)
            : ChatPermissions.readOnlyOnly());
        buildRegistry();
    }

    public CommandRegistry registry() {
        return registry;
    }

    public TerminalBuffer buffer() {
        return buffer;
    }

    public CommandHistory history() {
        return history;
    }

    public CommandAliases aliases() {
        return aliases;
    }

    public MacroStore macros() {
        return macros;
    }

    public boolean showTimestamps() {
        return showTimestamps.get();
    }

    /**
     * Runs a terminal line and writes its output to the buffer.
     *
     * @return a short result word: {@code ok}, {@code denied}, {@code unknown}, {@code empty}, {@code confirm}
     */
    public String execute(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) return "empty";

        String typed = rawLine.trim();
        if (registry.commands().isEmpty()) rebuild();

        String expanded = aliases.expand(typed);
        history.record(typed);
        buffer.input(typed);

        CommandLine parsed = CommandLine.parse(expanded);
        if (parsed == null) {
            buffer.error("empty command");
            return "empty";
        }

        TerminalContext context = new TerminalContext(parsed, buffer::output, buffer::error);
        String result = registry.dispatch(context);

        publish(result.equals("denied") ? AutomationEvent.Type.CHAT_COMMAND_DENIED
            : AutomationEvent.Type.CHAT_COMMAND, Map.of("command", parsed.name(), "result", result,
            "origin", "terminal"));
        return result;
    }

    /** Tab-completion candidates for the text typed so far. */
    public List<String> suggest(String input) {
        if (registry.commands().isEmpty()) rebuild();
        return registry.complete(input);
    }

    // Registry

    private void buildRegistry() {
        registry.clear();

        registry.register(TerminalCommand.builder("help", "help [command]",
            "list the terminal commands, or explain one", ChatPermissions.Level.READ_ONLY)
            .completions(index -> index == 0 ? registry.names() : List.of())
            .build(), this::help);

        registry.register(TerminalCommand.builder("status", "status",
            "show the overall automation status", ChatPermissions.Level.READ_ONLY)
            .build(), this::status);

        registry.register(TerminalCommand.builder("modules", "modules [list|search <q>|enable <module>|disable <module>]",
            "list, search, enable or disable Meteor modules", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("search", ChatPermissions.Level.READ_ONLY)
            .sub("enable", ChatPermissions.Level.CONTROL)
            .sub("disable", ChatPermissions.Level.CONTROL)
            .completions(index -> index == 0 ? List.of("list", "search", "enable", "disable") : moduleNames())
            .build(), this::modules);

        registry.register(TerminalCommand.builder("tasks", "tasks [list|status|stop <id>|cancel <id>|clear]",
            "inspect and control the automation task queue", ChatPermissions.Level.READ_ONLY)
            .aliases("task")
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("stop", ChatPermissions.Level.CONTROL)
            .sub("cancel", ChatPermissions.Level.CONTROL)
            .sub("clear", ChatPermissions.Level.CONTROL, true)
            .completions(index -> index == 0 ? List.of("list", "status", "stop", "cancel", "clear") : List.of())
            .build(), this::tasks);

        registry.register(TerminalCommand.builder("storage",
            "storage [list|search <item>|withdraw <item> [amount]|deposit <item> [amount]|register|goto <id>|restock]",
            "inspect the container registry and move items", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("search", ChatPermissions.Level.READ_ONLY)
            .sub("withdraw", ChatPermissions.Level.MOVEMENT)
            .sub("deposit", ChatPermissions.Level.MOVEMENT)
            .sub("register", ChatPermissions.Level.MOVEMENT)
            .sub("goto", ChatPermissions.Level.MOVEMENT)
            .sub("restock", ChatPermissions.Level.MOVEMENT)
            .completions(index -> index == 0
                ? List.of("list", "search", "withdraw", "deposit", "register", "goto", "restock") : List.of())
            .build(), this::storage);

        registry.register(TerminalCommand.builder("navigate", "navigate <x> <y> <z> [fly]",
            "ask the navigation layer to travel to a position", ChatPermissions.Level.MOVEMENT)
            .aliases("goto")
            .build(), this::navigate);

        registry.register(TerminalCommand.builder("baritone", "baritone [status|stop]",
            "show or stop the Baritone navigation", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("stop", ChatPermissions.Level.MOVEMENT)
            .build(), this::baritone);

        registry.register(TerminalCommand.builder("kit", "kit [list|status|claim <name>]",
            "list kits, their cooldowns, or claim one", ChatPermissions.Level.READ_ONLY)
            .aliases("kits")
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("claim", ChatPermissions.Level.CONTROL)
            .completions(index -> index == 0 ? List.of("list", "status", "claim") : kitNames())
            .build(), this::kit);

        registry.register(TerminalCommand.builder("elytra", "elytra [status|replace]",
            "show the elytra status or swap in the best spare", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("replace", ChatPermissions.Level.MOVEMENT)
            .build(), this::elytra);

        registry.register(TerminalCommand.builder("echest", "echest [status|open|organise|goto]",
            "inspect and drive the ender chest manager", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("open", ChatPermissions.Level.MOVEMENT)
            .sub("organise", ChatPermissions.Level.MOVEMENT)
            .sub("goto", ChatPermissions.Level.MOVEMENT)
            .build(), this::echest);

        registry.register(TerminalCommand.builder("player", "player [list|search <query>]",
            "list or search the tracked players", ChatPermissions.Level.READ_ONLY)
            .aliases("players")
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("search", ChatPermissions.Level.READ_ONLY)
            .build(), this::player);

        registry.register(TerminalCommand.builder("stash", "stash [list|nearby|search <query>]",
            "list or search the recorded stashes", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("nearby", ChatPermissions.Level.READ_ONLY)
            .sub("search", ChatPermissions.Level.READ_ONLY)
            .build(), this::stash);

        registry.register(TerminalCommand.builder("route", "route [list|add|clear|start|stop]",
            "manage the custom waypoint route", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("add", ChatPermissions.Level.MOVEMENT)
            .sub("clear", ChatPermissions.Level.CONTROL, true)
            .sub("start", ChatPermissions.Level.CONTROL)
            .sub("stop", ChatPermissions.Level.CONTROL)
            .build(), this::route);

        registry.register(TerminalCommand.builder("explore", "explore [grid|spiral|adaptive|heading|custom|stop|status]",
            "start, stop or inspect a Base Hunter V2 sweep", ChatPermissions.Level.READ_ONLY)
            .sub("grid", ChatPermissions.Level.CONTROL)
            .sub("spiral", ChatPermissions.Level.CONTROL)
            .sub("adaptive", ChatPermissions.Level.CONTROL)
            .sub("heading", ChatPermissions.Level.CONTROL)
            .sub("custom", ChatPermissions.Level.CONTROL)
            .sub("stop", ChatPermissions.Level.CONTROL)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .completions(index -> index == 0
                ? List.of("grid", "spiral", "adaptive", "heading", "custom", "stop", "status") : List.of())
            .build(), this::explore);

        registry.register(TerminalCommand.builder("chat", "chat [status|prefixes|commands|run <line>]",
            "inspect the chat prefix system, or run a chat command line", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("prefixes", ChatPermissions.Level.READ_ONLY)
            .sub("commands", ChatPermissions.Level.READ_ONLY)
            .sub("run", ChatPermissions.Level.CONTROL)
            .build(), this::chat);

        registry.register(TerminalCommand.builder("config", "config [list|get <module> <setting>|set <module> <setting> <value>]",
            "list modules, or read and change a module setting", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("get", ChatPermissions.Level.READ_ONLY)
            .sub("set", ChatPermissions.Level.CONTROL)
            .completions(index -> index == 1 ? moduleNames() : List.of())
            .build(), this::config);

        registry.register(TerminalCommand.builder("database", "database [status|export|import]",
            "inspect the saved database, or export/import it", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("export", ChatPermissions.Level.READ_ONLY)
            .sub("import", ChatPermissions.Level.CONTROL, true)
            .build(), this::database);

        registry.register(TerminalCommand.builder("history", "history [clear]",
            "show or clear the terminal command history", ChatPermissions.Level.READ_ONLY)
            .build(), this::history);

        registry.register(TerminalCommand.builder("macro", "macro [list|run <name>]",
            "run a named sequence of terminal commands", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("run", ChatPermissions.Level.CONTROL)
            .completions(index -> index == 0 ? List.of("list", "run") : macros.names())
            .build(), this::macro);

        registry.register(TerminalCommand.builder("clear", "clear",
            "clear the terminal output", ChatPermissions.Level.READ_ONLY)
            .build(), context -> buffer.clear());

        registry.register(TerminalCommand.builder("gui", "gui",
            "open the Base Hunting control screen", ChatPermissions.Level.READ_ONLY)
            .build(), context -> {
                BaseHuntingScreen.open(GuiThemes.get());
                context.info("Opened the Base Hunting screen.");
            });
    }

    // Handlers

    private void help(TerminalContext context) {
        if (context.argCount() == 0) {
            for (String line : registry.help()) context.info("%s", line);
            return;
        }
        for (String line : registry.help(context.arg(0))) context.info("%s", line);
    }

    private void status(TerminalContext context) {
        AutomationEngine engine = AutomationEngine.get();
        context.info("Server: %s  (%s)", ServerUtils.serverAddress(), ServerUtils.dimension());
        context.info("Position: %s", ServerUtils.coordinates());
        context.info("Automation: %s", engine == null ? "unavailable" : engine.statusLine());

        AutomationBrain brain = Modules.get().get(AutomationBrain.class);
        context.info("Brain: %s", brain == null || !brain.isActive() ? "inactive" : brain.statusLine());

        AutoStorage storage = Modules.get().get(AutoStorage.class);
        context.info("Auto-storage: %s", storage == null || !storage.isActive() ? "inactive" : storage.statusLine());

        KitBot kits = Modules.get().get(KitBot.class);
        context.info("Kits: %s", kits == null || !kits.isActive() ? "inactive" : kits.statusLine());

        context.info("Elytra: %s", ElytraManager.status());
        context.info("Spares: %d", ElytraManager.spareCount());

        NavigationManager navigation = NavigationManager.get();
        context.info("Navigation: %s", navigation == null ? "unavailable" : navigation.statusLine());

        StorageRegistry containers = StorageRegistry.get();
        context.info("Containers here: %d", containers == null ? 0 : containers.forCurrentServer().size());
    }

    private void modules(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        Modules modules = Modules.get();

        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                List<Module> ours = new ArrayList<>();
                for (Module module : modules.getAll()) {
                    if (module.addon instanceof BaseHuntingAddon) ours.add(module);
                }
                ours.sort(java.util.Comparator.comparing(module -> module.name));
                context.info("Base Hunting modules (%d):", ours.size());
                for (Module module : ours) {
                    context.info("  %-22s %s", module.name, module.isActive() ? "on" : "off");
                }
            }
            case "search" -> {
                if (context.argCount() < 2) {
                    context.error("usage: modules search <query>");
                    return;
                }
                String query = context.joinFrom(1).toLowerCase(Locale.ROOT);
                int found = 0;
                for (Module module : modules.getAll()) {
                    if (module.name.toLowerCase(Locale.ROOT).contains(query)
                        || module.title.toLowerCase(Locale.ROOT).contains(query)) {
                        context.info("  %-22s %s", module.name, module.isActive() ? "on" : "off");
                        found++;
                    }
                }
                if (found == 0) context.info("No modules match '%s'.", query);
            }
            case "enable", "disable" -> {
                if (context.argCount() < 2) {
                    context.error("usage: modules %s <module>", sub);
                    return;
                }
                Module module = modules.get(context.arg(1));
                if (module == null) {
                    context.error("no module named '%s'", context.arg(1));
                    return;
                }
                if (sub.equals("enable")) module.enable();
                else module.disable();
                context.info("%s is now %s.", module.name, module.isActive() ? "on" : "off");
            }
            default -> context.error("usage: modules [list|search <q>|enable <module>|disable <module>]");
        }
    }

    private void tasks(TerminalContext context) {
        AutomationEngine engine = AutomationEngine.get();
        if (engine == null) {
            context.error("the automation engine is not running");
            return;
        }

        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "list" : sub) {
            case "list", "status" -> {
                Task active = engine.queue().active();
                context.info("Active: %s", active == null ? "none" : active.describe());
                List<Task> queued = engine.queue().queued();
                context.info("Queued: %d", queued.size());
                for (Task task : queued) context.info("  %s", task.describe());
                List<Task> history = engine.queue().history();
                context.info("Finished: %d (showing up to 5)", history.size());
                for (Task task : history.subList(0, Math.min(5, history.size()))) {
                    context.info("  done: %s", task.describe());
                }
            }
            case "stop", "cancel" -> {
                if (context.argCount() < 2) {
                    context.error("usage: tasks %s <id>", sub);
                    return;
                }
                int id = context.intArg(1, -1);
                if (id < 0) {
                    context.error("'%s' is not a task id", context.arg(1));
                    return;
                }
                context.info(engine.cancel(id) ? "Cancelled task #%d.".formatted(id)
                    : "No active or queued task with id #%d.".formatted(id));
            }
            case "clear" -> {
                int count = engine.cancelAll("cleared from terminal");
                engine.queue().clearHistory();
                context.info("Cancelled %d task(s) and cleared the history.", count);
            }
            default -> context.error("usage: tasks [list|status|stop <id>|cancel <id>|clear]");
        }
    }

    private void storage(TerminalContext context) {
        StorageRegistry registryStore = StorageRegistry.get();
        String sub = context.arg(0).toLowerCase(Locale.ROOT);

        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                if (registryStore == null) {
                    context.error("the storage registry is not loaded");
                    return;
                }
                List<StorageRegistry.Container> containers = registryStore.forCurrentServer();
                context.info("Registered containers here: %d", containers.size());
                for (StorageRegistry.Container container : containers) {
                    context.info("  #%d %s %s (%s)%s", container.id, container.type,
                        container.pos.toShortString(), container.network,
                        container.verified ? "" : " unobserved");
                }
            }
            case "search" -> {
                if (context.argCount() < 2) {
                    context.error("usage: storage search <item>");
                    return;
                }
                if (registryStore == null) {
                    context.error("the storage registry is not loaded");
                    return;
                }
                String item = context.arg(1).toLowerCase(Locale.ROOT);
                int found = 0;
                for (StorageRegistry.Container container : registryStore.forCurrentServer()) {
                    int count = container.countOf(item);
                    if (count > 0) {
                        context.info("  #%d holds %d x %s", container.id, count, item);
                        found++;
                    }
                }
                if (found == 0) context.info("No observed container holds '%s'.", item);
            }
            case "withdraw", "deposit" -> {
                if (context.argCount() < 2) {
                    context.error("usage: storage %s <item> [amount]", sub);
                    return;
                }
                AutoStorage storage = Modules.get().get(AutoStorage.class);
                if (storage == null) {
                    context.error("the auto-storage module is not loaded");
                    return;
                }
                String item = context.arg(1);
                int amount = context.intArg(2, -1);
                context.info(sub.equals("withdraw") ? storage.withdraw(item, amount) : storage.deposit(item, amount));
            }
            case "register" -> {
                StorageNetwork network = Modules.get().get(StorageNetwork.class);
                if (network == null) {
                    context.error("the storage network module is not loaded");
                    return;
                }
                context.info(network.registerLooking());
            }
            case "goto" -> {
                StorageNetwork network = Modules.get().get(StorageNetwork.class);
                if (network == null) {
                    context.error("the storage network module is not loaded");
                    return;
                }
                int id = context.intArg(1, -1);
                if (id < 0) {
                    context.error("usage: storage goto <id>");
                    return;
                }
                context.info(network.gotoContainer(id));
            }
            case "restock" -> {
                dev.basehunt.bhclient.modules.RestockManager restock =
                    Modules.get().get(dev.basehunt.bhclient.modules.RestockManager.class);
                if (restock == null) {
                    context.error("the restock module is not loaded");
                    return;
                }
                context.info(context.argCount() > 1 ? restock.start(context.arg(1)) : restock.start());
            }
            default -> context.error("usage: storage [list|search|withdraw|deposit|register|goto|restock]");
        }
    }

    private void navigate(TerminalContext context) {
        if (context.argCount() < 3) {
            context.error("usage: navigate <x> <y> <z> [fly]");
            return;
        }
        NavigationManager navigation = NavigationManager.get();
        if (navigation == null) {
            context.error("the navigation layer is not running");
            return;
        }
        int x = context.intArg(0, Integer.MIN_VALUE);
        int y = context.intArg(1, Integer.MIN_VALUE);
        int z = context.intArg(2, Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            context.error("x, y and z must be whole numbers");
            return;
        }
        boolean fly = context.argCount() > 3 && context.arg(3).equalsIgnoreCase("fly");
        NavigationManager.Request request = navigation.start("terminal", new BlockPos(x, y, z), 3.0, fly);
        context.info("Navigation: %s", request.describe());
    }

    private void baritone(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> {
                context.info("Baritone available: %s", BaritoneHelper.AVAILABLE);
                if (!BaritoneHelper.health().isEmpty()) context.info("Health: %s", BaritoneHelper.health());
                context.info("Pathing: %s", BaritoneHelper.isPathing());
                context.info("Prefix: %s", BaritoneHelper.getPrefix());
            }
            case "stop" -> {
                BaritoneHelper.stop();
                context.info("Baritone stopped.");
            }
            default -> context.error("usage: baritone [status|stop]");
        }
    }

    private void kit(TerminalContext context) {
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) {
            context.error("the kit bot is not loaded");
            return;
        }
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "list" : sub) {
            case "list", "status" -> {
                for (String line : bot.describe()) context.info("%s", line);
            }
            case "claim" -> {
                if (context.argCount() < 2) {
                    context.error("usage: kit claim <name>");
                    return;
                }
                context.info(bot.claim(context.arg(1)));
            }
            default -> context.error("usage: kit [list|status|claim <name>]");
        }
    }

    private void elytra(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> {
                context.info("%s", ElytraManager.status());
                context.info("Spares: %d, best spare durability: %d", ElytraManager.spareCount(),
                    ElytraManager.bestSpareRemaining());
            }
            case "replace" -> {
                boolean replaced = ElytraManager.equipBest();
                context.info(replaced ? "Equipped the healthiest spare elytra."
                    : "No spare elytra to equip.");
            }
            default -> context.error("usage: elytra [status|replace]");
        }
    }

    private void echest(TerminalContext context) {
        EnderChestManager manager = Modules.get().get(EnderChestManager.class);
        if (manager == null) {
            context.error("the ender chest manager is not loaded");
            return;
        }
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> {
                context.info("%s", manager.statusLine());
                EnderChestStore store = EnderChestStore.get();
                context.info("Known chests here: %d", store == null ? 0 : store.forCurrentServer().size());
            }
            case "open" -> context.info(manager.openLookingAt()
                ? "Opening the ender chest you are looking at." : "No ender chest in reach.");
            case "organise" -> context.info(manager.organise()
                ? "Organising the open ender chest." : "Open an ender chest first.");
            case "goto" -> context.info(manager.navigateToNearest());
            default -> context.error("usage: echest [status|open|organise|goto]");
        }
    }

    private void player(TerminalContext context) {
        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) {
            context.error("the player tracker is not loaded");
            return;
        }
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        if (sub.equals("search")) {
            if (context.argCount() < 2) {
                context.error("usage: player search <query>");
                return;
            }
            List<PlayerTracker.Entry> matches = tracker.search(context.joinFrom(1));
            context.info("Matches: %d", matches.size());
            for (PlayerTracker.Entry entry : matches) {
                context.info("  %s (%s)", entry.name, entry.uuid);
            }
            return;
        }
        List<PlayerTracker.Entry> entries = tracker.forCurrentServer();
        context.info("Players tracked here: %d", entries.size());
        for (PlayerTracker.Entry entry : entries) {
            context.info("  %s last at %d, %d, %d", entry.name, entry.lastX, entry.lastY, entry.lastZ);
        }
    }

    private void stash(TerminalContext context) {
        StashManager stashes = StashManager.get();
        if (stashes == null) {
            context.error("the stash manager is not loaded");
            return;
        }
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        List<StashManager.Stash> list = stashes.forCurrentServer();
        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                context.info("Stashes here: %d", list.size());
                for (StashManager.Stash stash : list.subList(0, Math.min(10, list.size()))) {
                    context.info("  %s %s %s", stash.kind, stash.pos.toShortString(), stash.dimension);
                }
            }
            case "nearby" -> {
                context.info("Stashes here: %d", list.size());
                for (StashManager.Stash stash : list) {
                    context.info("  %s %s", stash.kind, stash.pos.toShortString());
                }
            }
            case "search" -> {
                if (context.argCount() < 2) {
                    context.error("usage: stash search <query>");
                    return;
                }
                String query = context.joinFrom(1).toLowerCase(Locale.ROOT);
                int found = 0;
                for (StashManager.Stash stash : list) {
                    if (stash.kind.name().toLowerCase(Locale.ROOT).contains(query)) {
                        context.info("  %s %s", stash.kind, stash.pos.toShortString());
                        found++;
                    }
                }
                if (found == 0) context.info("No stashes match '%s'.", query);
            }
            default -> context.error("usage: stash [list|nearby|search <query>]");
        }
    }

    private void route(TerminalContext context) {
        ExplorationStore store = ExplorationStore.get();
        if (store == null) {
            context.error("the exploration store is not loaded");
            return;
        }
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                List<ExplorationStore.Waypoint> waypoints = store.customWaypoints();
                context.info("Waypoints: %d", waypoints.size());
                for (int i = 0; i < waypoints.size(); i++) {
                    ExplorationStore.Waypoint waypoint = waypoints.get(i);
                    context.info("  [%d] %s %s %s", i, waypoint.label, waypoint.pos.toShortString(), waypoint.dimension);
                }
            }
            case "add" -> {
                if (ServerUtils.mc().player == null) {
                    context.error("no player");
                    return;
                }
                String label = context.argCount() > 1 ? context.arg(1) : "terminal";
                store.addWaypoint(label, ServerUtils.mc().player.getBlockPos(), ServerUtils.dimension());
                context.info("Waypoint added at %s.", ServerUtils.coordinates());
            }
            case "clear" -> {
                store.clearWaypoints();
                context.info("Route cleared.");
            }
            case "start" -> context.info(startV2(BaseHunterV2.ExploreMode.Custom));
            case "stop" -> {
                BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
                if (v2 != null) v2.stop();
                context.info("Route stopped.");
            }
            default -> context.error("usage: route [list|add|clear|start|stop]");
        }
    }

    private void explore(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        if (sub.isEmpty() || sub.equals("status")) {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            context.info("V2: %s", v2 == null || !v2.isStarted() ? "idle" : v2.getInfoString());
            return;
        }
        if (sub.equals("stop")) {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            if (v2 != null) v2.stop();
            context.info("Exploration stopped.");
            return;
        }
        BaseHunterV2.ExploreMode mode = switch (sub) {
            case "grid" -> BaseHunterV2.ExploreMode.Grid;
            case "spiral" -> BaseHunterV2.ExploreMode.Spiral;
            case "heading" -> BaseHunterV2.ExploreMode.Heading;
            case "custom" -> BaseHunterV2.ExploreMode.Custom;
            default -> BaseHunterV2.ExploreMode.Adaptive;
        };
        context.info(startV2(mode));
    }

    private String startV2(BaseHunterV2.ExploreMode mode) {
        BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
        if (v2 == null) return "Base Hunter V2 is not loaded.";
        if (!v2.isActive()) v2.enable();
        v2.setExploreMode(mode);
        v2.start();
        return "Started exploration in " + mode.name().toLowerCase(Locale.ROOT) + " mode.";
    }

    private void chat(TerminalContext context) {
        ChatCommands commands = Modules.get().get(ChatCommands.class);
        if (commands == null) {
            context.error("the chat command module is not loaded");
            return;
        }
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> context.info("Chat commands: %d registered. Use 'chat commands' to list them.",
                commands.registry().names().size());
            case "prefixes" -> context.info("Chat prefixes: %s", String.join(", ", commands.prefixes()));
            case "commands" -> context.info("Chat commands: %s", String.join(", ", commands.registry().names()));
            case "run" -> {
                if (context.argCount() < 2) {
                    context.error("usage: chat run <line>");
                    return;
                }
                context.info("Result: %s", commands.runLine(context.joinFrom(1)));
            }
            default -> context.error("usage: chat [status|prefixes|commands|run <line>]");
        }
    }

    private void config(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                int count = 0;
                for (Module module : Modules.get().getAll()) {
                    if (module.addon instanceof BaseHuntingAddon) count++;
                }
                context.info("Modules with settings: %d. Use 'config get <module> <setting>'.", count);
            }
            case "get" -> {
                if (context.argCount() < 3) {
                    context.error("usage: config get <module> <setting>");
                    return;
                }
                Module module = Modules.get().get(context.arg(1));
                if (module == null) {
                    context.error("no module named '%s'", context.arg(1));
                    return;
                }
                meteordevelopment.meteorclient.settings.Setting<?> setting = module.settings.get(context.arg(2));
                if (setting == null) {
                    context.error("module '%s' has no setting '%s'", module.name, context.arg(2));
                    return;
                }
                context.info("%s.%s = %s", module.name, setting.name, setting.get());
            }
            case "set" -> {
                if (context.argCount() < 4) {
                    context.error("usage: config set <module> <setting> <value>");
                    return;
                }
                Module module = Modules.get().get(context.arg(1));
                if (module == null) {
                    context.error("no module named '%s'", context.arg(1));
                    return;
                }
                meteordevelopment.meteorclient.settings.Setting<?> setting = module.settings.get(context.arg(2));
                if (setting == null) {
                    context.error("module '%s' has no setting '%s'", module.name, context.arg(2));
                    return;
                }
                boolean ok = setting.parse(context.joinFrom(3));
                context.info(ok ? "%s.%s = %s".formatted(module.name, setting.name, setting.get())
                    : "could not parse '%s' for %s.%s".formatted(context.joinFrom(3), module.name, setting.name));
            }
            default -> context.error("usage: config [list|get <module> <setting>|set <module> <setting> <value>]");
        }
    }

    private void database(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> {
                StashManager stashes = StashManager.get();
                PlayerTracker players = PlayerTracker.get();
                ExplorationStore exploration = ExplorationStore.get();
                context.info("Sections: %s", String.join(", ", DatabaseIO.sectionKeys()));
                context.info("Stashes: %,d", stashes == null ? 0 : stashes.getAll().size());
                context.info("Players: %,d", players == null ? 0 : players.getAll().size());
                context.info("Explored chunks: %,d", exploration == null ? 0 : exploration.regions().exploredCount());
                context.info("Export file: %s", DatabaseIO.exportFileExists()
                    ? DatabaseIO.exportFile().getName() : "none");
            }
            case "export" -> context.info(DatabaseIO.exportToFile()
                ? "Exported the database to " + DatabaseIO.exportFile().getName() + "."
                : "Export failed; see the log.");
            case "import" -> {
                int applied = DatabaseIO.importFromFile();
                context.info(applied > 0 ? "Imported %d record(s).".formatted(applied)
                    : "Nothing imported (no export file, or it was unreadable).");
            }
            default -> context.error("usage: database [status|export|import]");
        }
    }

    private void history(TerminalContext context) {
        if (context.argCount() > 0 && context.arg(0).equalsIgnoreCase("clear")) {
            history.clear();
            context.info("History cleared.");
            return;
        }
        List<String> entries = history.entries();
        context.info("History (%d):", entries.size());
        for (String entry : entries) context.info("  %s", entry);
    }

    // Helpers

    private void macro(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                if (macros.isEmpty()) {
                    context.info("No macros defined. Add them in the module's 'macros' setting.");
                    return;
                }
                context.info("Macros (%d):", macros.size());
                for (String name : macros.names()) {
                    context.info("  %s = %s", name, String.join(" ; ", macros.steps(name)));
                }
            }
            case "run" -> {
                if (context.argCount() < 2) {
                    context.error("usage: macro run <name>");
                    return;
                }
                String name = context.arg(1);
                List<String> steps = macros.steps(name);
                if (steps.isEmpty()) {
                    context.error("no macro named '%s'", name);
                    return;
                }
                if (macroDepth >= 3) {
                    context.error("macros are nested too deeply");
                    return;
                }
                macroDepth++;
                try {
                    for (String step : steps) {
                        context.info("> %s", step);
                        // Replay each step through the registry so permissions and confirmation still apply.
                        if (execute(step).equals("denied")) break;
                    }
                } finally {
                    macroDepth--;
                }
            }
            default -> context.error("usage: macro [list|run <name>]");
        }
    }

    private List<String> moduleNames() {
        List<String> names = new ArrayList<>();
        for (Module module : Modules.get().getAll()) names.add(module.name);
        java.util.Collections.sort(names);
        return names;
    }

    private List<String> kitNames() {
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) return List.of();
        List<String> names = new ArrayList<>();
        bot.registry().all().forEach(kit -> names.add(kit.name()));
        return names;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
