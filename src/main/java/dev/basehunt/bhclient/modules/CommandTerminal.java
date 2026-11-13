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
import dev.basehunt.bhclient.modules.SixbtHomeManager;
import dev.basehunt.bhclient.modules.SixbtHotspotTracker;
import dev.basehunt.bhclient.modules.SixbtPlayerTracker;
import dev.basehunt.bhclient.modules.SixbtTpaManager;
import dev.basehunt.bhclient.modules.SixbtTpsMonitor;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.DatabaseIO;
import dev.basehunt.bhclient.systems.DeathStore;
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

        registry.register(TerminalCommand.builder("job", "job [status|stop|mine <block> [qty]|goto <x> <y> <z>|follow <player>|explore [x z]|farm [range]|tunnel|build <file> <x> <y> <z>|run <line>]",
            "run and inspect Baritone jobs (mine, follow, explore, goto, farm, build, tunnel)",
            ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("stop", ChatPermissions.Level.MOVEMENT)
            .sub("mine", ChatPermissions.Level.MOVEMENT)
            .sub("goto", ChatPermissions.Level.MOVEMENT)
            .sub("follow", ChatPermissions.Level.MOVEMENT)
            .sub("explore", ChatPermissions.Level.MOVEMENT)
            .sub("farm", ChatPermissions.Level.MOVEMENT)
            .sub("tunnel", ChatPermissions.Level.MOVEMENT)
            .sub("build", ChatPermissions.Level.MOVEMENT)
            .sub("run", ChatPermissions.Level.MOVEMENT)
            .completions(index -> index == 0
                ? List.of("status", "stop", "mine", "goto", "follow", "explore", "farm", "tunnel", "build", "run")
                : List.of())
            .build(), this::job);

        registry.register(TerminalCommand.builder("death", "death [list|near|recover|recover-id <id>|clear]",
            "inspect the recorded deaths and fly back to recover one", ChatPermissions.Level.READ_ONLY)
            .aliases("deaths")
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("near", ChatPermissions.Level.READ_ONLY)
            .sub("recover", ChatPermissions.Level.MOVEMENT)
            .sub("recover-id", ChatPermissions.Level.MOVEMENT)
            .sub("clear", ChatPermissions.Level.CONTROL, true)
            .completions(index -> index == 0
                ? List.of("list", "near", "recover", "recover-id", "clear") : List.of())
            .build(), this::death);

        registry.register(TerminalCommand.builder("radar", "radar [status|list]",
            "list the players currently near you", ChatPermissions.Level.READ_ONLY)
            .aliases("nearby")
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .build(), this::radar);

        registry.register(TerminalCommand.builder("look", "look [status|player <name>|at <x> <y> <z>|yaw <deg> <pitch>|clear]",
            "turn the camera smoothly toward a target instead of snapping to it",
            ChatPermissions.Level.MOVEMENT)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("clear", ChatPermissions.Level.READ_ONLY)
            .sub("player", ChatPermissions.Level.MOVEMENT)
            .sub("at", ChatPermissions.Level.MOVEMENT)
            .sub("yaw", ChatPermissions.Level.MOVEMENT)
            .build(), this::look);

        registry.register(TerminalCommand.builder("human", "human [status|on|off|profile]",
            "idle with the small, irregular movements of a real player",
            ChatPermissions.Level.MOVEMENT)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("on", ChatPermissions.Level.MOVEMENT)
            .sub("off", ChatPermissions.Level.MOVEMENT)
            .sub("profile", ChatPermissions.Level.READ_ONLY)
            .build(), this::human);

        registry.register(TerminalCommand.builder("kit", "kit [list|status|claim <name>|delivery|deliver <player> <kit>|loadout ...|restock <kit>|whitelist ...]",
            "claim kits, or run the 6b6t kit-maker delivery role", ChatPermissions.Level.READ_ONLY)
            .aliases("kits")
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("claim", ChatPermissions.Level.CONTROL)
            .sub("delivery", ChatPermissions.Level.READ_ONLY)
            .sub("deliver", ChatPermissions.Level.MOVEMENT)
            .sub("loadout", ChatPermissions.Level.READ_ONLY)
            .sub("restock", ChatPermissions.Level.READ_ONLY)
            .sub("queue", ChatPermissions.Level.READ_ONLY)
            .sub("whitelist", ChatPermissions.Level.CONTROL)
            .sub("messages", ChatPermissions.Level.READ_ONLY)
            .sub("message", ChatPermissions.Level.CONTROL)
            .sub("source", ChatPermissions.Level.READ_ONLY)
            .completions(index -> index == 0
                ? List.of("list", "status", "claim", "delivery", "deliver", "loadout", "restock", "queue",
                    "whitelist", "messages", "message", "source")
                : kitNames())
            .build(), this::kit);

        registry.register(TerminalCommand.builder("sixbt", "sixbt [status|home|tpa|tps|hotspot|players|server|gui]",
            "6b6t server tools: homes, teleport requests, TPS, hotspots and players", ChatPermissions.Level.READ_ONLY)
            .aliases("6b6t")
            .sub("status", ChatPermissions.Level.READ_ONLY)
            .sub("home", ChatPermissions.Level.READ_ONLY)
            .sub("tpa", ChatPermissions.Level.READ_ONLY)
            .sub("tps", ChatPermissions.Level.READ_ONLY)
            .sub("hotspot", ChatPermissions.Level.READ_ONLY)
            .sub("players", ChatPermissions.Level.READ_ONLY)
            .sub("server", ChatPermissions.Level.READ_ONLY)
            .sub("gui", ChatPermissions.Level.READ_ONLY)
            .completions(index -> index == 0
                ? List.of("status", "home", "tpa", "tps", "hotspot", "players", "server", "gui")
                : List.of())
            .build(), this::sixbt);

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
            "open the Baritone Anarchy Expansion control screen", ChatPermissions.Level.READ_ONLY)
            .build(), context -> {
                BaseHuntingScreen.open(GuiThemes.get());
                context.info("Opened the Baritone Anarchy Expansion screen.");
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
                context.info("Baritone Anarchy Expansion modules (%d):", ours.size());
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

    private void job(TerminalContext context) {
        BaritoneControl control = Modules.get().get(BaritoneControl.class);
        if (control == null) {
            context.error("the Baritone control module is not loaded");
            return;
        }

        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> {
                for (String line : control.describe()) context.info("%s", line);
            }
            case "stop" -> {
                control.cancel();
                context.info("Baritone job cancelled.");
            }
            case "mine", "goto", "follow", "explore", "farm", "tunnel", "build" -> {
                if (!control.isActive()) control.enable();
                context.info("%s", control.run(context.joinFrom(0)));
            }
            case "run" -> {
                if (context.argCount() < 2) {
                    context.error("usage: job run <line>");
                    return;
                }
                if (!control.isActive()) control.enable();
                context.info("%s", control.run(context.joinFrom(1)));
            }
            default -> context.error("usage: job [status|stop|mine <block> [qty]|goto <x> <y> <z>|follow <player>|explore [x z]|farm [range]|tunnel|build <file> <x> <y> <z>|run <line>]");
        }
    }

    private void death(TerminalContext context) {
        DeathRecovery recovery = Modules.get().get(DeathRecovery.class);
        if (recovery == null) {
            context.error("the death recovery module is not loaded");
            return;
        }

        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "list" : sub) {
            case "list" -> {
                for (String line : recovery.describe()) context.info("%s", line);
            }
            case "near" -> context.info("%s", recovery.statusLine());
            case "recover" -> {
                if (!recovery.isActive()) recovery.enable();
                context.info("%s", recovery.recoverNearest());
            }
            case "recover-id" -> {
                if (context.argCount() < 2) {
                    context.error("usage: death recover-id <id>");
                    return;
                }
                int id = context.intArg(1, -1);
                if (id < 0) {
                    context.error("id must be a whole number");
                    return;
                }
                if (!recovery.isActive()) recovery.enable();
                context.info("%s", recovery.recoverById(id));
            }
            case "clear" -> {
                DeathStore store = DeathStore.get();
                if (store == null) {
                    context.error("the death store is not loaded");
                    return;
                }
                int removed = store.removeWhere(record -> true);
                context.info("Cleared %d death record(s).", removed);
            }
            default -> context.error("usage: death [list|near|recover|recover-id <id>|clear]");
        }
    }

    private void radar(TerminalContext context) {
        PlayerRadar radar = Modules.get().get(PlayerRadar.class);
        if (radar == null) {
            context.error("the player radar module is not loaded");
            return;
        }
        if (!radar.isActive()) radar.enable();
        for (String line : radar.describe()) context.info("%s", line);
    }

    private void look(TerminalContext context) {
        SmoothLook look = Modules.get().get(SmoothLook.class);
        if (look == null) {
            context.error("the smooth-look module is not loaded");
            return;
        }

        if (context.argCount() == 0 || context.arg(0).equals("status")) {
            if (!look.isActive()) look.enable();
            context.info("smooth look: %s", look.statusLine());
            return;
        }

        String sub = context.arg(0);
        switch (sub) {
            case "clear" -> {
                look.clearTarget();
                context.info("look target cleared");
            }
            case "player" -> {
                if (context.argCount() < 2) {
                    context.error("usage: look player <name>");
                    return;
                }
                if (!look.isActive()) look.enable();
                String name = context.arg(1);
                var target = findPlayer(name);
                if (target == null) {
                    context.error("no player named %s is in view", name);
                    return;
                }
                look.lookAt(target.getX(), target.getEyeY(), target.getZ());
                context.info("turning smoothly toward %s", name);
            }
            case "at" -> {
                if (context.argCount() < 4) {
                    context.error("usage: look at <x> <y> <z>");
                    return;
                }
                if (!look.isActive()) look.enable();
                look.lookAt(context.intArg(1, 0), context.intArg(2, 0), context.intArg(3, 0));
                context.info("turning smoothly toward %s %s %s", context.arg(1), context.arg(2), context.arg(3));
            }
            case "yaw" -> {
                if (context.argCount() < 3) {
                    context.error("usage: look yaw <degrees> <pitch>");
                    return;
                }
                if (!look.isActive()) look.enable();
                look.look(context.intArg(1, 0), context.intArg(2, 0));
                context.info("turning smoothly to yaw %s pitch %s", context.arg(1), context.arg(2));
            }
            default -> context.error("usage: look [status|player <name>|at <x> <y> <z>|yaw <deg> <pitch>|clear]");
        }
    }

    private void human(TerminalContext context) {
        HumanBehaviorModule human = Modules.get().get(HumanBehaviorModule.class);
        if (human == null) {
            context.error("the human-behavior module is not loaded");
            return;
        }

        if (context.argCount() == 0 || context.arg(0).equals("status")) {
            context.info("human behavior: %s (%s, %d gestures)", human.isActive() ? "on" : "off",
                human.statusLine(), human.gestures());
            return;
        }

        switch (context.arg(0)) {
            case "on" -> {
                if (!human.isActive()) human.enable();
                context.info("human behavior enabled");
            }
            case "off" -> {
                if (human.isActive()) human.disable();
                context.info("human behavior disabled");
            }
            case "profile" -> {
                BehaviorMonitor monitor = Modules.get().get(BehaviorMonitor.class);
                if (monitor == null) {
                    context.error("the behavior monitor module is not loaded");
                    return;
                }
                if (!monitor.isActive()) monitor.enable();
                context.info("human-likeness: %s", monitor.describe());
            }
            default -> context.error("usage: human [status|on|off|profile]");
        }
    }

    private net.minecraft.entity.player.PlayerEntity findPlayer(String name) {
        if (mc.player == null || mc.world == null) return null;
        for (var player : mc.world.getPlayers()) {
            if (player.getGameProfile().name().equalsIgnoreCase(name)) return player;
        }
        return null;
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
            case "delivery" -> {
                for (String line : bot.describeDelivery()) context.info("%s", line);
            }
            case "deliver" -> {
                if (context.argCount() < 3) {
                    context.error("usage: kit deliver <player> <kit>");
                    return;
                }
                context.info(bot.deliverTo(context.arg(1), context.arg(2)));
            }
            case "queue" -> {
                if (context.argCount() >= 2 && context.arg(1).equalsIgnoreCase("clear")) {
                    context.info(bot.clearQueue());
                } else {
                    for (String line : bot.describeDelivery()) context.info("%s", line);
                }
            }
            case "loadout" -> kitLoadout(context, bot);
            case "whitelist" -> kitWhitelist(context, bot);
            case "messages" -> {
                for (String line : bot.describeMessages()) context.info("%s", line);
            }
            case "message" -> {
                if (context.argCount() < 4) {
                    context.error("usage: kit message <kind> <text>");
                    return;
                }
                context.info(bot.setMessage(context.arg(1), context.arg(2)));
            }
            case "source" -> {
                if (context.argCount() < 2) {
                    context.error("usage: kit source <kit>");
                    return;
                }
                context.info(bot.describeKitSource(context.arg(1)));
            }
            case "restock" -> {
                if (context.argCount() < 2) {
                    context.error("usage: kit restock <kit>");
                    return;
                }
                for (String line : bot.restockFor(context.arg(1)).describe()) context.info("%s", line);
            }
            default -> context.error(
                "usage: kit [list|status|claim|delivery|deliver|loadout|restock|queue|whitelist|messages|"
                    + "message|source]");
        }
    }

    /** {@code kit whitelist <list|add|remove|block|unblock|open|close> [player]} */
    private void kitWhitelist(TerminalContext context, KitBot bot) {
        String sub = context.argCount() >= 2 ? context.arg(1).toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "list" -> {
                for (String line : bot.describeAccess()) context.info("%s", line);
            }
            case "add", "remove", "block", "unblock" -> {
                if (context.argCount() < 3) {
                    context.error("usage: kit whitelist " + sub + " <player>");
                    return;
                }
                String player = context.arg(2);
                context.info(switch (sub) {
                    case "add" -> bot.addToWhitelist(player);
                    case "remove" -> bot.removeFromWhitelist(player);
                    case "block" -> bot.addToBlacklist(player);
                    default -> bot.removeFromBlacklist(player);
                });
            }
            case "open" -> {
                bot.setAllowAnyone(true);
                context.info("kit bot is now open to anyone");
            }
            case "close" -> {
                bot.setAllowAnyone(false);
                context.info("kit bot is now whitelist-only");
            }
            default -> context.error("usage: kit whitelist [list|add|remove|block|unblock|open|close] [player]");
        }
    }

    /** {@code kit loadout <list|show|add|remove|create|delete> ...} */
    private void kitLoadout(TerminalContext context, KitBot bot) {
        String action = context.arg(1).toLowerCase(Locale.ROOT);
        switch (action.isEmpty() ? "list" : action) {
            case "list" -> {
                for (String line : bot.loadouts().describe()) context.info("%s", line);
            }
            case "show" -> {
                if (context.argCount() < 3) {
                    context.error("usage: kit loadout show <kit>");
                    return;
                }
                for (String line : bot.loadouts().findOrDefault(context.arg(2)).describe()) context.info("%s", line);
            }
            case "add" -> {
                if (context.argCount() < 4) {
                    context.error("usage: kit loadout add <kit> <item> [count]");
                    return;
                }
                context.info(bot.addLoadoutItem(context.arg(2), context.arg(3), context.intArg(4, 1)));
            }
            case "remove" -> {
                if (context.argCount() < 4) {
                    context.error("usage: kit loadout remove <kit> <item>");
                    return;
                }
                context.info(bot.removeLoadoutItem(context.arg(2), context.arg(3)));
            }
            case "create" -> {
                if (context.argCount() < 3) {
                    context.error("usage: kit loadout create <kit>");
                    return;
                }
                context.info(bot.createLoadout(context.arg(2)));
            }
            case "delete" -> {
                if (context.argCount() < 3) {
                    context.error("usage: kit loadout delete <kit>");
                    return;
                }
                context.info(bot.deleteLoadout(context.arg(2)));
            }
            default -> context.error("usage: kit loadout [list|show|add|remove|create|delete]");
        }
    }

    /** {@code sixbt <group> ...} */
    private void sixbt(TerminalContext context) {
        String sub = context.arg(0).toLowerCase(Locale.ROOT);
        switch (sub.isEmpty() ? "status" : sub) {
            case "status" -> {
                context.info("server: %s (%s)", ServerUtils.serverAddress(),
                    dev.basehunt.bhclient.core.sixbt.SixbtCommand.isSixbtServer(ServerUtils.serverAddress())
                        ? "6b6t" : "not 6b6t");
                context.info("position: %s", ServerUtils.coordinates());
                sixbtModuleLine(context, KitBot.class, bot -> "kit delivery: " + bot.deliveryStatusLine());
                sixbtModuleLine(context, SixbtHomeManager.class, SixbtHomeManager::statusLine);
                sixbtModuleLine(context, SixbtTpaManager.class, SixbtTpaManager::statusLine);
                sixbtModuleLine(context, SixbtTpsMonitor.class, SixbtTpsMonitor::statusLine);
                sixbtModuleLine(context, SixbtHotspotTracker.class, SixbtHotspotTracker::statusLine);
                sixbtModuleLine(context, SixbtPlayerTracker.class, SixbtPlayerTracker::statusLine);
            }
            case "home" -> {
                SixbtHomeManager homes = Modules.get().get(SixbtHomeManager.class);
                if (homes == null) {
                    context.error("the home manager is not loaded");
                    return;
                }
                String action = context.arg(1).toLowerCase(Locale.ROOT);
                switch (action.isEmpty() ? "list" : action) {
                    case "list" -> homes.describe().forEach(line -> context.info("%s", line));
                    case "set" -> context.info(homes.setHome(context.arg(2)));
                    case "goto", "visit" -> context.info(homes.visitHome(context.arg(2)));
                    case "del", "delete" -> context.info(homes.deleteHome(context.arg(2)));
                    case "cancel" -> context.info(homes.cancel());
                    default -> context.error("usage: sixbt home [list|set <name>|goto <name>|del <name>|cancel]");
                }
            }
            case "tpa" -> {
                SixbtTpaManager tpa = Modules.get().get(SixbtTpaManager.class);
                if (tpa == null) {
                    context.error("the tpa manager is not loaded");
                    return;
                }
                String action = context.arg(1).toLowerCase(Locale.ROOT);
                switch (action) {
                    case "send", "to" -> context.info(tpa.request(context.arg(2)));
                    case "cancel" -> context.info(tpa.cancel());
                    case "accept", "yes" -> context.info(tpa.accept(context.arg(2)));
                    case "decline", "no" -> context.info(tpa.decline(context.arg(2)));
                    default -> tpa.describe().forEach(line -> context.info("%s", line));
                }
            }
            case "tps" -> {
                SixbtTpsMonitor tps = Modules.get().get(SixbtTpsMonitor.class);
                if (tps == null) {
                    context.error("the TPS monitor is not loaded");
                    return;
                }
                if (context.argCount() >= 2 && context.arg(1).equalsIgnoreCase("poll")) {
                    if (mc.player != null && mc.player.networkHandler != null) {
                        mc.player.networkHandler.sendChatMessage("/tps");
                    }
                    context.info("polled /tps");
                } else {
                    tps.describe().forEach(line -> context.info("%s", line));
                }
            }
            case "hotspot" -> {
                SixbtHotspotTracker hotspot = Modules.get().get(SixbtHotspotTracker.class);
                if (hotspot == null) {
                    context.error("the hotspot tracker is not loaded");
                    return;
                }
                String action = context.arg(1).toLowerCase(Locale.ROOT);
                switch (action) {
                    case "join" -> context.info(hotspot.join());
                    case "create" -> context.info(hotspot.create());
                    case "cancel" -> context.info(hotspot.cancel());
                    case "info" -> context.info(hotspot.requestInfo());
                    default -> hotspot.describe().forEach(line -> context.info("%s", line));
                }
            }
            case "players" -> {
                SixbtPlayerTracker tracker = Modules.get().get(SixbtPlayerTracker.class);
                if (tracker == null) {
                    context.error("the player tracker is not loaded");
                    return;
                }
                tracker.describe().forEach(line -> context.info("%s", line));
            }
            case "server" -> {
                String query = context.arg(1);
                var entries = dev.basehunt.bhclient.core.sixbt.SixbtCommand.all();
                for (var entry : entries) {
                    if (!query.isEmpty() && !entry.name().contains(query.toLowerCase(Locale.ROOT))) continue;
                    context.info("/%s - %s", entry.name(), entry.summary());
                }
            }
            case "gui" -> {
                dev.basehunt.bhclient.gui.SixbtMenuScreen.open(
                    meteordevelopment.meteorclient.gui.GuiThemes.get());
                context.info("opened the 6b6t menu");
            }
            default -> context.error("usage: sixbt [status|home|tpa|tps|hotspot|players|server|gui]");
        }
    }

    private <T extends Module> void sixbtModuleLine(TerminalContext context, Class<T> type,
                                                   java.util.function.Function<T, String> line) {
        T module = Modules.get().get(type);
        context.info("%s", module == null ? "(not loaded)" : line.apply(module));
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
