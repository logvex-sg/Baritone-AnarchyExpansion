package dev.basehunt.bhclient.core.rule;

import java.util.Locale;

/**
 * A condition that decides whether a {@link Rule} fires.
 *
 * <p>Conditions are written as a small expression over named facts the runtime publishes, e.g.
 * {@code "health < 8"}, {@code "elytra_durability < 10"}, {@code "inventory_free <= 1"}. Keeping the language
 * tiny and total (a malformed condition is false, never an exception) means a rule the user mistypes disables
 * itself instead of breaking the tick loop.
 *
 * <p>The evaluation is pure: it takes a fact lookup and returns a boolean. That is what lets the "health below
 * threshold" and "inventory full" conditions be covered by the headless suite without a client.
 */
public final class Condition {
    /** How the two sides are compared. */
    public enum Operator {
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">="),
        EQ("=="),
        NE("!=");

        public final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        static Operator from(String text) {
            for (Operator operator : values()) if (operator.symbol.equals(text)) return operator;
            return null;
        }
    }

    private final String fact;
    private final Operator operator;
    private final double value;
    private final boolean malformed;
    private final String raw;

    private Condition(String fact, Operator operator, double value, boolean malformed, String raw) {
        this.fact = fact;
        this.operator = operator;
        this.value = value;
        this.malformed = malformed;
        this.raw = raw;
    }

    /**
     * Parses a condition such as {@code "health < 8"}. Returns a condition that is always false when the text
     * cannot be parsed, so a bad rule is inert rather than dangerous.
     */
    public static Condition parse(String text) {
        if (text == null || text.isBlank()) return new Condition("", null, 0, true, "");
        String raw = text.trim();

        String[] symbols = {"<=", ">=", "==", "!=", "<", ">"};
        for (String symbol : symbols) {
            int index = raw.indexOf(symbol);
            if (index <= 0) continue;

            String fact = raw.substring(0, index).trim().toLowerCase(Locale.ROOT);
            String rhs = raw.substring(index + symbol.length()).trim();
            Operator operator = Operator.from(symbol);
            if (operator == null || fact.isEmpty() || rhs.isEmpty()) continue;

            try {
                double number = Double.parseDouble(rhs);
                return new Condition(fact, operator, number, false, raw);
            } catch (NumberFormatException e) {
                return new Condition(fact, operator, 0, true, raw);
            }
        }

        return new Condition("", null, 0, true, raw);
    }

    /** True when the condition never fires because it could not be parsed. */
    public boolean malformed() {
        return malformed;
    }

    public String fact() {
        return fact;
    }

    public String raw() {
        return raw;
    }

    /**
     * Evaluates the condition against a fact lookup. A missing fact is treated as zero, and a comparison
     * against a fact that does not exist is false rather than a crash, so a rule referencing an unavailable
     * fact simply does not fire.
     */
    public boolean test(FactSource source) {
        if (malformed || source == null) return false;

        Double actual = source.fact(fact);
        if (actual == null) return false;

        return switch (operator) {
            case LT -> actual < value;
            case LE -> actual <= value;
            case GT -> actual > value;
            case GE -> actual >= value;
            case EQ -> Math.abs(actual - value) < 1e-9;
            case NE -> Math.abs(actual - value) >= 1e-9;
        };
    }

    /** Reads named numeric facts. */
    @FunctionalInterface
    public interface FactSource {
        Double fact(String name);
    }

    @Override
    public String toString() {
        return malformed ? "invalid(" + raw + ")" : "%s %s %s".formatted(fact, operator.symbol, format(value));
    }

    private static String format(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value) ? String.valueOf((long) value)
            : String.valueOf(value);
    }
}
