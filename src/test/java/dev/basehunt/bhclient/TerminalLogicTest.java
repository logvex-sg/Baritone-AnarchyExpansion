package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.chat.ChatPermissions;
import dev.basehunt.bhclient.core.terminal.CommandAliases;
import dev.basehunt.bhclient.core.terminal.CommandHistory;
import dev.basehunt.bhclient.core.terminal.CommandLine;
import dev.basehunt.bhclient.core.terminal.CommandRegistry;
import dev.basehunt.bhclient.core.terminal.MacroStore;
import dev.basehunt.bhclient.core.terminal.TerminalBuffer;
import dev.basehunt.bhclient.core.terminal.TerminalCommand;
import dev.basehunt.bhclient.core.terminal.TerminalContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless tests for the in-game command terminal: the line parser, the history cursor, the shortcut table,
 * and the registry's dispatch, aliases, permission levels, confirmation gate and tab completion.
 *
 * <p>Like the other suites these touch no Minecraft classes - a handler writes to a list-backed sink - so the
 * whole terminal contract is checked in a plain JVM.
 */
public class TerminalLogicTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        parser();
        history();
        aliases();
        registryDispatch();
        registryAliases();
        permissionLevels();
        confirmation();
        completion();
        macros();
        bufferBounds();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // Parser

    private static void parser() {
        section("command line parser");

        check("blank parses to null", CommandLine.parse("   ") == null, "parsed");
        check("null parses to null", CommandLine.parse(null) == null, "parsed");

        CommandLine simple = CommandLine.parse("withdraw ender_pearl 16");
        check("name is lower-cased", simple.name().equals("withdraw"), "got " + simple.name());
        check("args are split", simple.argCount() == 2, "got " + simple.argCount());
        check("first arg is right", simple.arg(0).equals("ender_pearl"), "got " + simple.arg(0));
        check("second arg is right", simple.arg(1).equals("16"), "got " + simple.arg(1));
        check("intArg parses a number", simple.intArg(1, -1) == 16, "got " + simple.intArg(1, -1));
        check("intArg falls back on text", simple.intArg(0, -1) == -1, "got " + simple.intArg(0, -1));

        CommandLine quoted = CommandLine.parse("chat run \"!status now\"");
        check("quotes group an argument", quoted.argCount() == 2, "got " + quoted.argCount());
        check("quoted arg keeps its spaces", quoted.arg(1).equals("!status now"), "got " + quoted.arg(1));

        CommandLine escaped = CommandLine.parse("say a\\ b");
        check("backslash escapes a space", escaped.arg(0).equals("a b"), "got " + escaped.arg(0));

        CommandLine prefixed = CommandLine.parse("/status");
        check("a leading slash is stripped", prefixed.name().equals("status"), "got " + prefixed.name());
        CommandLine dotted = CommandLine.parse(".bh");
        check("leading dots are stripped", dotted.name().equals("bh"), "got " + dotted.name());
        check("an empty command after stripping is null", CommandLine.parse("///") == null, "parsed");

        CommandLine joined = CommandLine.parse("modules search ender chest");
        check("joinFrom rebuilds the tail", joined.joinFrom(1).equals("ender chest"), "got " + joined.joinFrom(1));
        check("arg past the end is empty", joined.arg(9).isEmpty(), "got " + joined.arg(9));
    }

    // History

    private static void history() {
        section("command history");

        CommandHistory history = new CommandHistory();
        check("a fresh history is empty", history.isEmpty(), "not empty");

        history.record("status");
        history.record("tasks list");
        history.record("status");
        check("history records entries", history.size() == 3, "got " + history.size());
        check("the latest entry is last", history.latest().equals("status"), "got " + history.latest());

        history.record("status");
        check("a consecutive duplicate is collapsed", history.size() == 3, "got " + history.size());

        history.record("   ");
        check("a blank line is ignored", history.size() == 3, "got " + history.size());

        check("up returns the newest", history.previous("draft").equals("status"), "wrong");
        check("up again returns the previous", history.previous("").equals("tasks list"), "wrong");
        check("up reaches the oldest", history.previous("").equals("status"), "wrong");
        check("up at the oldest stays put", history.previous("").equals("status"), "wrong");
        check("down returns towards the newest", history.next("").equals("tasks list"), "wrong");
        check("down reaches the newest", history.next("").equals("status"), "wrong");
        check("down past the newest restores the draft", history.next("").equals("draft"), "wrong");
        check("navigating is false after leaving history", !history.navigating(), "navigating");

        history.record("x");
        check("recording resets navigation", !history.navigating(), "navigating");

        history.clear();
        check("clear empties the history", history.isEmpty(), "not empty");

        CommandHistory bounded = new CommandHistory();
        for (int i = 0; i < 260; i++) bounded.record("cmd" + i);
        check("history is bounded", bounded.size() <= 200, "got " + bounded.size());
        check("the newest survives bounding", bounded.latest().equals("cmd259"), "got " + bounded.latest());
    }

    // Aliases

    private static void aliases() {
        section("command shortcuts");

        CommandAliases aliases = new CommandAliases();
        aliases.put("s", "status");
        aliases.put("g", "withdraw ender_pearl");
        aliases.put("W", "withdraw");  // upper-cased key must be normalised to "w"

        check("a shortcut expands", aliases.expand("s").equals("status"), "got " + aliases.expand("s"));
        check("a shortcut with a built-in argument appends the rest",
            aliases.expand("g 16").equals("withdraw ender_pearl 16"), "got " + aliases.expand("g 16"));
        check("shortcut lookup is case-insensitive",
            aliases.expand("W 5").equals("withdraw 5"), "got " + aliases.expand("W 5"));
        check("a leading prefix is ignored for lookup", aliases.expand(".s").equals("status"), "got " + aliases.expand(".s"));
        check("an unknown token is left alone", aliases.expand("status").equals("status"), "got " + aliases.expand("status"));
        check("a blank line is left alone", aliases.expand("   ").equals("   "), "got '" + aliases.expand("   ") + "'");

        aliases.remove("s");
        check("a removed shortcut no longer expands", aliases.expand("s").equals("s"), "got " + aliases.expand("s"));
        check("all() reflects the table", aliases.all().containsKey("w"), "missing w");
    }

    // Registry

    private static void registryDispatch() {
        section("registry dispatch");

        CommandRegistry registry = new CommandRegistry();
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();

        registry.register(TerminalCommand.builder("echo", "echo <text>", "echo the text",
            ChatPermissions.Level.READ_ONLY).build(), context -> context.info("echo:" + context.joinFrom(0)));

        check("a registered command runs", run(registry, out, err, "echo hello world").equals("ok"), "not ok");
        check("the handler saw its argument", out.contains("echo:hello world"), "got " + out);

        out.clear();
        err.clear();
        check("an unknown command is reported", run(registry, out, err, "nope").equals("unknown"), "not unknown");
        check("an unknown command writes an error", !err.isEmpty(), "no error");
        check("an unknown command writes no output", out.isEmpty(), "wrote output");

        check("an empty line is empty", run(registry, out, err, "").equals("empty"), "not empty");
    }

    private static void registryAliases() {
        section("registry aliases");

        CommandRegistry registry = new CommandRegistry();
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();

        registry.register(TerminalCommand.builder("storage", "storage ...", "storage things",
            ChatPermissions.Level.READ_ONLY).aliases("st", "chest").build(),
            context -> context.info("storage-ran"));

        check("the primary name resolves", registry.find("storage") != null, "missing");
        check("an alias resolves", registry.find("st") != null, "missing");
        check("a second alias resolves", registry.find("chest") != null, "missing");
        check("names() includes aliases", registry.names().contains("chest"), "missing");

        check("an alias runs the handler", run(registry, out, err, "st").equals("ok"), "not ok");
        check("the alias reached the handler", out.contains("storage-ran"), "got " + out);
    }

    private static void permissionLevels() {
        section("registry permissions");

        CommandRegistry registry = new CommandRegistry();
        registry.register(TerminalCommand.builder("status", "status", "show status",
            ChatPermissions.Level.READ_ONLY).build(), context -> context.info("ok"));

        // Subcommand-specific levels: "list" is safe, "withdraw" is not.
        registry.register(TerminalCommand.builder("storage", "storage [list|withdraw]",
            "storage things", ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("withdraw", ChatPermissions.Level.MOVEMENT)
            .build(), context -> context.info("storage-ran"));

        registry.setPermissions(ChatPermissions.readOnlyOnly());
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();

        check("a read-only command is allowed", run(registry, out, err, "status").equals("ok"), "denied");
        check("a read-only subcommand is allowed", run(registry, out, err, "storage list").equals("ok"), "denied");
        check("a movement subcommand is refused", run(registry, out, err, "storage withdraw").equals("denied"), "allowed");
        check("a refusal writes an error", !err.isEmpty(), "no error");

        registry.setPermissions(ChatPermissions.of(java.util.Set.of("storage"), false));
        err.clear();
        check("an allow-listed command runs its movement subcommand",
            run(registry, out, err, "storage withdraw").equals("ok"), "denied");
    }

    private static void confirmation() {
        section("registry confirmation");

        CommandRegistry registry = new CommandRegistry();
        registry.setPermissions(ChatPermissions.of(java.util.Set.of("*"), true));
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();
        int[] runs = {0};

        registry.register(TerminalCommand.builder("wipe", "wipe", "dangerous",
            ChatPermissions.Level.CONTROL).confirm().build(), context -> runs[0]++);

        check("the first invocation asks for confirmation",
            run(registry, out, err, "wipe").equals("confirm"), "ran");
        check("the first invocation did not run the handler", runs[0] == 0, "ran");
        check("the second invocation runs", run(registry, out, err, "wipe").equals("ok"), "not ok");
        check("the handler ran once", runs[0] == 1, "ran " + runs[0]);

        // A different command between the two clears the armed confirmation.
        registry.register(TerminalCommand.builder("noop", "noop", "noop",
            ChatPermissions.Level.READ_ONLY).build(), context -> { });
        run(registry, out, err, "wipe");
        run(registry, out, err, "noop");
        check("an intervening command disarms the confirmation",
            run(registry, out, err, "wipe").equals("confirm"), "ran");
    }

    private static void completion() {
        section("tab completion");

        CommandRegistry registry = new CommandRegistry();
        registry.register(TerminalCommand.builder("status", "status", "status",
            ChatPermissions.Level.READ_ONLY).build(), context -> { });
        registry.register(TerminalCommand.builder("storage", "storage [list|withdraw]", "storage",
            ChatPermissions.Level.READ_ONLY)
            .sub("list", ChatPermissions.Level.READ_ONLY)
            .sub("withdraw", ChatPermissions.Level.MOVEMENT)
            .build(), context -> { });
        registry.register(TerminalCommand.builder("stash", "stash", "stash",
            ChatPermissions.Level.READ_ONLY).build(), context -> { });

        List<String> commands = registry.complete("sta");
        check("completion lists every matching command",
            commands.contains("status") && commands.contains("stash") && commands.contains("storage"),
            "got " + commands);
        check("completion returns full lines", commands.contains("status"), "got " + commands);

        List<String> subs = registry.complete("storage ");
        check("completion lists subcommands", subs.contains("storage list") && subs.contains("storage withdraw"),
            "got " + subs);

        List<String> partialSub = registry.complete("storage l");
        check("completion filters subcommands by prefix", partialSub.contains("storage list"), "got " + partialSub);
        check("completion filters out the others", !partialSub.contains("storage withdraw"), "got " + partialSub);

        check("completion of an unknown command is empty", registry.complete("zzz ").isEmpty(), "not empty");
        check("completion of a blank line lists commands", !registry.complete("").isEmpty(), "empty");
    }

    private static void macros() {
        section("terminal macros");

        MacroStore macros = new MacroStore();
        check("a fresh store is empty", macros.isEmpty(), "not empty");

        macros.addLine("tidy = storage list ; tasks list ; status");
        check("a macro is stored", macros.has("tidy"), "missing");
        check("lookup is case-insensitive", macros.has("TIDY"), "missing");
        check("steps are split on the semicolon", macros.steps("tidy").size() == 3, "got " + macros.steps("tidy"));
        check("a step keeps its spaces", macros.steps("tidy").get(0).equals("storage list"),
            "got " + macros.steps("tidy").get(0));
        check("an unknown macro has no steps", macros.steps("nope").isEmpty(), "had steps");
        check("names() lists the macro", macros.names().contains("tidy"), "missing");

        macros.addLine("bad");
        macros.addLine("= no name");
        macros.addLine("empty =  ;  ; ");
        check("a line without a name is ignored", macros.size() == 1, "got " + macros.size());
        check("a macro with no steps is ignored", !macros.has("empty"), "stored");

        macros.clear();
        check("clear empties the store", macros.isEmpty(), "not empty");
    }

    private static void bufferBounds() {
        section("terminal buffer");

        TerminalBuffer buffer = new TerminalBuffer();
        buffer.input("status");
        buffer.output("line a\nline b");
        buffer.error("boom");

        check("input is echoed with a marker", buffer.lines().get(0).text().startsWith("> "), "not marked");
        check("multi-line output is split", buffer.lines().get(2).text().equals("line b"), "not split");
        check("errors are recorded", buffer.lines().get(buffer.size() - 1).kind() == TerminalBuffer.Kind.ERROR,
            "wrong kind");

        for (int i = 0; i < 500; i++) buffer.output("spam " + i);
        check("the buffer is bounded", buffer.size() <= 400, "got " + buffer.size());

        check("tail returns at most the requested lines", buffer.tail(5).size() == 5, "got " + buffer.tail(5).size());

        buffer.clear();
        check("clear empties the buffer", buffer.size() == 0, "got " + buffer.size());
    }

    // Helpers

    private static String run(CommandRegistry registry, List<String> out, List<String> err, String line) {
        CommandLine parsed = CommandLine.parse(line);
        if (parsed == null) return registry.dispatch(null);
        TerminalContext context = new TerminalContext(parsed, out::add, err::add);
        return registry.dispatch(context);
    }
}
