package dev.basehunt.bhclient.core.chat;

import java.util.List;

/**
 * Everything a chat command handler needs: the parsed line and where to write its reply.
 *
 * <p>The output is a pair of sinks rather than a chat API, so a handler can be exercised in a test with a
 * list-backed sink and no client. That is what makes "a refused command writes nothing" and "an unknown
 * command writes an error" assertable.
 */
public final class ChatContext {
    private final ChatRouter.Parsed parsed;
    private final String prefix;
    private final java.util.function.Consumer<String> info;
    private final java.util.function.Consumer<String> error;

    public ChatContext(ChatRouter.Parsed parsed, String prefix,
                       java.util.function.Consumer<String> info,
                       java.util.function.Consumer<String> error) {
        this.parsed = parsed;
        this.prefix = prefix;
        this.info = info == null ? message -> { } : info;
        this.error = error == null ? message -> { } : error;
    }

    public String name() {
        return parsed.name();
    }

    public String prefix() {
        return prefix;
    }

    public List<String> args() {
        return parsed.args();
    }

    public String arg(int index) {
        return parsed.arg(index);
    }

    public int argCount() {
        return parsed.args().size();
    }

    public String joinFrom(int index) {
        return parsed.joinFrom(index);
    }

    public String raw() {
        return parsed.raw();
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

    /** Parses an argument as an integer, or returns the fallback when it is not one. */
    public int intArg(int index, int fallback) {
        String value = arg(index);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
