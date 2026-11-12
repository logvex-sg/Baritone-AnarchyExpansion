package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds a registered kit inside the containers the bot can see.
 *
 * <p>A kit bot does not carry its stock; it is restocked from storage. When a request arrives the bot walks
 * the nearby containers it has already read and looks for the items of the requested kit. This class is the
 * decision: given the observed contents of several containers and a loadout, which container holds the kit,
 * and is the whole kit present?
 *
 * <p>It is deliberately a pure function over snapshots rather than a live world reader, so "the kit is in the
 * second barrel", "the chest only holds part of the kit" and "no container has it" are all decided - and
 * tested - without a client. Reading the world is the runtime's job; deciding is this class's.
 *
 * <p>Partial matches are reported rather than hidden: a bot that silently delivers half a kit is worse than
 * one that says it is not stocked.
 */
public final class ChestKitSearch {

    /** A container the runtime has read, identified by its block position. */
    public record Container(String label, int x, int y, int z, List<KitLoadout.ItemSpec> contents) {
        public Container {
            label = label == null || label.isBlank() ? "container" : label;
            contents = contents == null ? List.of() : List.copyOf(contents);
        }

        public String coords() {
            return x + ", " + y + ", " + z;
        }
    }

    /** The result of a search. */
    public record Result(String label, int x, int y, int z, int found, int needed, List<String> missing) {
        public boolean hasSource() {
            return label != null;
        }

        public boolean complete() {
            return hasSource() && missing.isEmpty();
        }

        public String coords() {
            return x + ", " + y + ", " + z;
        }

        /** A one-line summary for chat and the terminal. */
        public String summary() {
            if (!hasSource()) return "no container holds " + needed + " item(s)";
            if (complete()) return label + " at " + coords() + " holds the whole kit (" + found + " items)";
            return label + " at " + coords() + " holds " + found + "/" + needed
                + " (missing " + String.join(", ", missing) + ")";
        }
    }

    private ChestKitSearch() {
    }

    /**
     * Finds the container that holds the most of {@code loadout}.
     *
     * <p>The container with the highest item count wins, so a barrel with the whole kit beats a chest with one
     * spare. When nothing is found the result carries the shortfall so the bot can report it.
     */
    public static Result find(List<Container> containers, KitLoadout loadout) {
        if (loadout == null || loadout.isEmpty()) {
            return new Result(null, 0, 0, 0, 0, 0, List.of());
        }
        if (containers == null || containers.isEmpty()) {
            return new Result(null, 0, 0, 0, 0, loadout.totalItems(), missing(loadout, List.of()));
        }

        Result best = null;
        for (Container container : containers) {
            Result candidate = inspect(container, loadout);
            if (best == null || (candidate.hasSource() && (!best.hasSource() || candidate.found() > best.found()))) {
                best = candidate;
            }
        }
        return best == null ? new Result(null, 0, 0, 0, 0, loadout.totalItems(), missing(loadout, List.of()))
            : best;
    }

    /** The contents of one container measured against a loadout. */
    public static Result inspect(Container container, KitLoadout loadout) {
        if (container == null || loadout == null || loadout.isEmpty()) {
            return new Result(null, 0, 0, 0, 0, 0, List.of());
        }

        int found = 0;
        for (KitLoadout.ItemSpec spec : loadout.items()) {
            int have = countOf(container.contents(), spec.itemId());
            found += Math.min(have, spec.count());
        }
        if (found == 0) {
            return new Result(null, container.x(), container.y(), container.z(), 0, loadout.totalItems(),
                missing(loadout, container.contents()));
        }
        return new Result(container.label(), container.x(), container.y(), container.z(), found,
            loadout.totalItems(), missing(loadout, container.contents()));
    }

    /** The loadout items not present in a container's contents, as {@code id xN} lines. */
    public static List<String> missing(KitLoadout loadout, List<KitLoadout.ItemSpec> contents) {
        List<String> missing = new ArrayList<>();
        if (loadout == null) return missing;
        for (KitLoadout.ItemSpec spec : loadout.items()) {
            int have = countOf(contents, spec.itemId());
            if (have < spec.count()) {
                int shortfall = spec.count() - have;
                missing.add(shortfall > 1 ? spec.itemId() + " x" + shortfall : spec.itemId());
            }
        }
        return missing;
    }

    /** How many of {@code itemId} a container holds. */
    public static int countOf(List<KitLoadout.ItemSpec> contents, String itemId) {
        if (contents == null || itemId == null) return 0;
        String needle = itemId.toLowerCase(Locale.ROOT);
        int total = 0;
        for (KitLoadout.ItemSpec spec : contents) {
            if (spec != null && spec.itemId().equals(needle)) total += spec.count();
        }
        return total;
    }
}
