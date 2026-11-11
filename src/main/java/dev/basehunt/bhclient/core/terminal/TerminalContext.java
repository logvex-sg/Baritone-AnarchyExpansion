package dev.basehunt.bhclient.core.terminal;

import dev.basehunt.bhclient.core.chat.ChatPermissions;

import java.util.List;

/**
 * Everything a terminal command handler needs: the parsed line, where to write its reply, and the prefix.
 *
 * <p>The output is a pair of sinks rather than a chat API, so a handler can be exercised in a test with a
 * list-backed sink and no client. That is what makes "a denied command writes nothing" and "an unknown
 * command writes an error" assertable without a Minecraft instance.
 */
public final class TerminalContext {
    private final CommandLine line;
    private final java.util.function.Consumer<String> info;
    private final java.util.function.Consumer<String> error;

    public TerminalContext(CommandLine line,
                           java.util.function.Consumer<String> info,
                           java.util.function.Consumer<String> error) {
        this.line = line;
        this.info = info == null ? message -> { } : info;
        this.error = error == null ? message -> { } : error;
    }

    public String name() {
        return line.name();
    }

    public List<String> args() {
        return line.args();
    }

    public String arg(int index) {
        return line.arg(index);
    }

    public int argCount() {
        return line.argCount();
    }

    public String joinFrom(int index) {
        return line.joinFrom(index);
    }

    public String raw() {
        return line.raw();
    }

    public int intArg(int index, int fallback) {
        return line.intArg(index, fallback);
    }

    public void info(String message) {
        info.accept(message);
    }

    public void info(String format, Object... args) {
        info.accept(format.formatted(args));
    }

    public void error(String message) {
        error.accept(message);
    }

    public void error(String format, Object... args) {
        error.accept(format.formatted(args));
    }

    /** A level the registry uses when a command declares none. */
    public static ChatPermissions.Level defaultLevel() {
        return ChatPermissions.Level.CONTROL;
    }
}
