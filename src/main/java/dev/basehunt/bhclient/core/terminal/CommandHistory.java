package dev.basehunt.bhclient.core.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, deduplicated command history with the cursor navigation a terminal input box needs.
 *
 * <p>The history is a value the input widget walks with the up and down keys. Navigating keeps the line the
 * user was typing as a draft, so pressing up then down returns to it rather than losing it. The store is
 * bounded so a long session cannot grow it without limit, and consecutive duplicates are collapsed the way a
 * shell does.
 */
public final class CommandHistory {
    private static final int LIMIT = 200;

    private final List<String> entries = new ArrayList<>();
    private int cursor = -1;
    private String draft = "";

    /** Records a line as the newest entry. Blank lines and a repeat of the newest entry are ignored. */
    public void record(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(trimmed)) {
            reset();
            return;
        }
        entries.add(trimmed);
        while (entries.size() > LIMIT) entries.remove(0);
        reset();
    }

    /** Resets the navigation cursor; called after a command runs so the next up starts at the newest line. */
    public void reset() {
        cursor = -1;
        draft = "";
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    /** The most recent entry, or an empty string. */
    public String latest() {
        return entries.isEmpty() ? "" : entries.get(entries.size() - 1);
    }

    /**
     * Moves one step towards the past.
     *
     * @param current the text currently in the input box, kept as the draft so down can restore it
     * @return the line to show, or the current text when there is no older entry
     */
    public String previous(String current) {
        if (entries.isEmpty()) return current == null ? "" : current;
        if (cursor == -1) {
            draft = current == null ? "" : current;
            cursor = entries.size() - 1;
        } else if (cursor > 0) {
            cursor--;
        }
        return entries.get(cursor);
    }

    /**
     * Moves one step towards the present.
     *
     * @param current the text currently in the input box, used when there is no navigation in progress
     * @return the line to show, or the draft once the newest entry has been passed
     */
    public String next(String current) {
        if (cursor == -1) return current == null ? "" : current;
        if (cursor < entries.size() - 1) {
            cursor++;
            return entries.get(cursor);
        }
        cursor = -1;
        String restored = draft;
        draft = "";
        return restored;
    }

    /** True when the cursor is walking the history rather than sitting on the input line. */
    public boolean navigating() {
        return cursor != -1;
    }

    public void clear() {
        entries.clear();
        reset();
    }
}
