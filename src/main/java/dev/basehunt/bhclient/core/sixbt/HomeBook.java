package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A local model of the homes a player has on 6b6t, and how close they are to the rank limit.
 *
 * <p>6b6t gives every player homes ({@code /sethome}, {@code /home <name>}) and a limit that grows with rank
 * and voting; AnarchyMod adds two more. The server owns the real list, but the client can remember what it
 * created and seen, which is enough to warn before {@code /sethome} fails and to pick a sensible name.
 *
 * <p>This is a pure book: adding, renaming and removing a home, and reporting how many slots are free. It
 * never sends anything; the module does that and then updates the book.
 */
public final class HomeBook {

    /** One remembered home. */
    public record Home(String name, String dimension, int x, int y, int z, long createdAt) {
        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }

        public String describe() {
            return "%s [%s] %s".formatted(name, dimension, coords());
        }
    }

    private final Map<String, Home> homes = new LinkedHashMap<>();
    private int limit = 4;

    public HomeBook() {
    }

    public HomeBook(int limit) {
        this.limit = Math.max(0, limit);
    }

    public int limit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = Math.max(0, limit);
    }

    public int size() {
        return homes.size();
    }

    public int freeSlots() {
        return Math.max(0, limit - homes.size());
    }

    public boolean isFull() {
        return homes.size() >= limit;
    }

    public Home find(String name) {
        if (name == null) return null;
        return homes.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public boolean contains(String name) {
        return find(name) != null;
    }

    public List<Home> all() {
        return new ArrayList<>(homes.values());
    }

    /**
     * Adds or replaces a home.
     *
     * @return true when the home was stored; false when the name is blank or the book is full and this is a
     *         new home (an existing home may always be replaced)
     */
    public boolean put(Home home) {
        if (home == null || home.name() == null || home.name().isBlank()) return false;
        boolean existing = contains(home.name());
        if (!existing && isFull()) return false;
        homes.put(home.name().toLowerCase(Locale.ROOT), home);
        return true;
    }

    /** Renames a home, keeping its position. Returns false when the old name is unknown. */
    public boolean rename(String oldName, String newName) {
        Home home = find(oldName);
        if (home == null || newName == null || newName.isBlank()) return false;
        if (!oldName.equalsIgnoreCase(newName) && contains(newName)) return false;

        homes.remove(oldName.toLowerCase(Locale.ROOT));
        homes.put(newName.toLowerCase(Locale.ROOT),
            new Home(newName, home.dimension(), home.x(), home.y(), home.z(), home.createdAt()));
        return true;
    }

    public boolean remove(String name) {
        if (name == null) return false;
        return homes.remove(name.trim().toLowerCase(Locale.ROOT)) != null;
    }

    /** The home nearest a position, or null when there are none. */
    public Home nearest(int x, int z) {
        Home best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Home home : homes.values()) {
            long dx = (long) home.x() - x;
            long dz = (long) home.z() - z;
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = home;
            }
        }
        return best;
    }

    public void clear() {
        homes.clear();
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("%d/%d homes used".formatted(size(), limit));
        for (Home home : homes.values()) lines.add("  " + home.describe());
        if (homes.isEmpty()) lines.add("  (none yet - /bh 6b6t home add <name>)");
        return lines;
    }
}
