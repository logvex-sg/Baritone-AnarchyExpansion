package dev.basehunt.bhclient.core.logic;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a stack matches what the user asked for.
 *
 * <p>A withdrawal or deposit is described by an item id, a category, or a free-text term, and the same filter
 * has to be applied to stacks in a container and to stacks in the player's inventory. Keeping the matching
 * rule in one pure class is what makes "withdraw 64 ender pearls" behave the same whether the pearls are in
 * the first chest or the fourth.
 *
 * <p>Matching is deliberately conservative. A term matches on the full item id, the namespace-stripped id, or
 * the display name, and it matches as a whole word within an id ({@code "pearl"} matches {@code ender_pearl}
 * but {@code "ear"} does not). A bare number or a category name is never treated as an item, so a typo fails
 * loudly with "no match" instead of quietly moving the wrong thing.
 */
public final class ItemFilter {

    /** How a filter was expressed, for reporting which one matched. */
    public enum Mode {
        ITEM,
        CATEGORY,
        TERM,
        ANY
    }

    private final Mode mode;
    private final String term;
    private final String normalised;
    private final StorageCategory category;

    private ItemFilter(Mode mode, String term, StorageCategory category) {
        this.mode = mode;
        this.term = term == null ? "" : term;
        this.normalised = normalise(this.term);
        this.category = category;
    }

    /** Match one specific item, by full id, bare id or display name. */
    public static ItemFilter item(String itemId) {
        return new ItemFilter(Mode.ITEM, itemId, null);
    }

    /** Match every item in a storage category. */
    public static ItemFilter category(StorageCategory category) {
        return new ItemFilter(Mode.CATEGORY, category == null ? "" : category.name(), category);
    }

    /** Match by a free-text term against the id or the display name. */
    public static ItemFilter term(String text) {
        return new ItemFilter(Mode.TERM, text, null);
    }

    /** Match anything. Used by "deposit all" and "withdraw everything". */
    public static ItemFilter any() {
        return new ItemFilter(Mode.ANY, "", null);
    }

    public Mode mode() {
        return mode;
    }

    public String term() {
        return term;
    }

    public StorageCategory category() {
        return category;
    }

    /** True when a stack's ids and name satisfy this filter. */
    public boolean matches(String fullItemId, String displayName, StorageCategory stackCategory) {
        if (mode == Mode.ANY) return true;

        if (mode == Mode.CATEGORY) {
            return category != null && stackCategory == category;
        }

        if (normalised.isEmpty()) return false;

        String full = normalise(fullItemId);
        if (full.equals(normalised)) return true;

        // A namespace-stripped exact id: "minecraft:ender_pearl" matches "ender_pearl".
        if (full.contains(":")) {
            String bare = full.substring(full.indexOf(':') + 1);
            if (bare.equals(normalised)) return true;
        }

        if (mode == Mode.TERM) {
            String name = normalise(displayName);
            if (!name.isEmpty() && name.contains(normalised)) return true;
            // Match the term as a whole word inside the id so "pearl" finds ender_pearl but "ear" does not.
            if (full.contains("_" + normalised) || full.contains(normalised + "_")) return true;
        }

        return false;
    }

    /** True when any of the filters matches. An empty set matches nothing. */
    public static boolean anyMatch(Set<ItemFilter> filters, String fullItemId, String displayName,
                                   StorageCategory stackCategory) {
        if (filters == null || filters.isEmpty()) return false;
        for (ItemFilter filter : filters) {
            if (filter.matches(fullItemId, displayName, stackCategory)) return true;
        }
        return false;
    }

    /** Parses a comma/space separated list of terms into filters, recognising categories too. */
    public static Set<ItemFilter> parseList(String raw) {
        Set<ItemFilter> filters = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return filters;

        for (String token : raw.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            StorageCategory category = dev.basehunt.bhclient.utils.CategoryParser.one(token);
            if (category != null) {
                filters.add(category(category));
            } else {
                filters.add(term(token));
            }
        }
        return filters;
    }

    private static String normalise(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ItemFilter filter)) return false;
        return mode == filter.mode && normalised.equals(filter.normalised) && category == filter.category;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(mode, normalised, category);
    }

    @Override
    public String toString() {
        return switch (mode) {
            case ANY -> "any";
            case CATEGORY -> "category:" + (category == null ? "?" : category.name().toLowerCase(Locale.ROOT));
            case ITEM -> "item:" + term;
            case TERM -> "term:" + term;
        };
    }
}
