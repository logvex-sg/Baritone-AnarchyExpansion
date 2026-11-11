package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out how much of each tracked supply is missing and where to fetch it.
 *
 * <p>Restocking is deliberately "top up to max, never below min": a rule says an item must not drop below
 * {@code min} and should be brought back up to {@code max}. The planner only ever plans to move the
 * difference, and it only plans a fetch when the required amount actually exists in reachable storage, so
 * a restock request cannot silently over-pull or promise items that are not there.
 *
 * <p>Amounts are item counts, not slots: a fetch of 32 fireworks may span several storage stacks.
 */
public final class RestockPlanner {

    /** A restock rule for one item. */
    public record Rule(String itemKey, String name, int min, int max, boolean emergency) {
        public int target() {
            return Math.max(min, max);
        }
    }

    /** A stack of a given item available in a specific container. */
    public record Stock(int containerId, String containerLabel, String itemKey, int count) {
    }

    /** One item that needs topping up. */
    public record Need(String itemKey, String name, int carried, int required, boolean emergency) {
    }

    /** One planned fetch of {@code amount} of an item from a container. */
    public record Fetch(String itemKey, String name, int amount, int containerId, String containerLabel) {
    }

    public record Plan(List<Need> needs, List<Fetch> fetches, List<String> warnings) {
        public boolean isEmpty() {
            return fetches.isEmpty();
        }
    }

    private RestockPlanner() {
    }

    /** A named set of rules — a restock profile. */
    public record Profile(String name, List<Rule> rules) {
    }

    /** The profiles that ship with the addon. */
    public static List<Profile> profiles() {
        return List.of(
            new Profile("travel", List.of(
                new Rule("firework_rocket", "Firework Rocket", 32, 64, false),
                new Rule("cooked_beef", "Steak", 16, 64, false),
                new Rule("golden_carrot", "Golden Carrot", 8, 32, false),
                new Rule("totem_of_undying", "Totem of Undying", 1, 2, true),
                new Rule("ender_pearl", "Ender Pearl", 4, 16, false)
            )),
            new Profile("building", List.of(
                new Rule("cobblestone", "Cobblestone", 128, 512, false),
                new Rule("oak_planks", "Oak Planks", 64, 256, false),
                new Rule("torch", "Torch", 32, 128, false),
                new Rule("scaffolding", "Scaffolding", 32, 128, false)
            )),
            new Profile("hunting", List.of(
                new Rule("firework_rocket", "Firework Rocket", 48, 128, false),
                new Rule("totem_of_undying", "Totem of Undying", 1, 3, true),
                new Rule("golden_apple", "Golden Apple", 4, 16, false),
                new Rule("experience_bottle", "Bottle o' Enchanting", 8, 32, false)
            )),
            new Profile("emergency", List.of(
                new Rule("totem_of_undying", "Totem of Undying", 1, 2, true),
                new Rule("enchanted_golden_apple", "Enchanted Golden Apple", 1, 4, true),
                new Rule("golden_apple", "Golden Apple", 4, 16, true)
            ))
        );
    }

    /** Looks up a profile by name, case-insensitively. */
    public static Profile profile(String name) {
        if (name == null) return null;
        for (Profile profile : profiles()) {
            if (profile.name().equalsIgnoreCase(name.trim())) return profile;
        }
        return null;
    }

    /**
     * @param carried       how many of each item the player currently holds, keyed by item id
     * @param stock         every stack of every tracked item available in reachable storage
     * @param rules         the restock rules
     * @param freeSlots     empty player inventory slots (limits how many fetches can be planned)
     */
    public static Plan plan(Map<String, Integer> carried, List<Stock> stock, List<Rule> rules, int freeSlots) {
        List<String> warnings = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        List<Fetch> fetches = new ArrayList<>();

        // Aggregate available stock per item, keeping the source containers so a fetch can name where it came
        // from. The order of stock determines which container is preferred first.
        Map<String, List<Stock>> byItem = new LinkedHashMap<>();
        for (Stock entry : stock) {
            if (entry.count() <= 0) continue;
            byItem.computeIfAbsent(entry.itemKey(), k -> new ArrayList<>()).add(entry);
        }

        int slotsLeft = Math.max(0, freeSlots);

        for (Rule rule : rules) {
            int have = carried.getOrDefault(rule.itemKey(), 0);
            if (have >= rule.min()) continue;

            int required = rule.target() - have;
            if (required <= 0) continue;

            List<Stock> sources = byItem.getOrDefault(rule.itemKey(), List.of());
            int available = sources.stream().mapToInt(Stock::count).sum();

            needs.add(new Need(rule.itemKey(), rule.name(), have, required, rule.emergency()));

            if (available <= 0) {
                warnings.add("No %s in reachable storage (%d needed).".formatted(rule.name(), required));
                continue;
            }

            if (slotsLeft <= 0) {
                warnings.add("Inventory is full; %s could not be restocked.".formatted(rule.name()));
                continue;
            }

            int remaining = Math.min(required, available);

            for (Stock source : sources) {
                if (remaining <= 0 || slotsLeft <= 0) break;
                int take = Math.min(remaining, source.count());
                fetches.add(new Fetch(rule.itemKey(), rule.name(), take, source.containerId(), source.containerLabel()));
                remaining -= take;
                // One fetch is one shift-click worth of slots; approximate a slot per fetch for the budget.
                slotsLeft--;
            }

            if (remaining > 0) {
                warnings.add("Only %d of the %d %s needed could be sourced."
                    .formatted(required - remaining, required, rule.name()));
            }
        }

        return new Plan(needs, fetches, warnings);
    }
}
