package dev.basehunt.bhclient.core.terminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The scrollback the terminal shows and the handlers write to.
 *
 * <p>Lines carry a kind so the GUI can colour errors differently from ordinary output, and a timestamp so the
 * user can see when something happened. The buffer is bounded, because a command that runs every tick would
 * otherwise grow it without limit.
 */
public final class TerminalBuffer {
    /** What a line is, for colouring and filtering. */
    public enum Kind { INPUT, OUTPUT, ERROR, INFO }

    /** One line of scrollback. */
    public record Line(Kind kind, String text, long timestamp) {
    }

    private static final int LIMIT = 400;

    private final Deque<Line> lines = new ArrayDeque<>();

    public synchronized void add(Kind kind, String text) {
        if (text == null) return;
        for (String part : text.split("\n", -1)) {
            lines.addLast(new Line(kind == null ? Kind.OUTPUT : kind, part, System.currentTimeMillis()));
        }
        while (lines.size() > LIMIT) lines.removeFirst();
    }

    public synchronized void input(String text) {
        add(Kind.INPUT, "> " + text);
    }

    public synchronized void output(String text) {
        add(Kind.OUTPUT, text);
    }

    public synchronized void error(String text) {
        add(Kind.ERROR, text);
    }

    public synchronized void info(String text) {
        add(Kind.INFO, text);
    }

    /** Every line, oldest first. */
    public synchronized List<Line> lines() {
        return new ArrayList<>(lines);
    }

    /** The last {@code count} lines, oldest first. */
    public synchronized List<Line> tail(int count) {
        List<Line> all = new ArrayList<>(lines);
        if (count >= all.size()) return all;
        return new ArrayList<>(all.subList(all.size() - count, all.size()));
    }

    public synchronized int size() {
        return lines.size();
    }

    public synchronized void clear() {
        lines.clear();
    }
}
