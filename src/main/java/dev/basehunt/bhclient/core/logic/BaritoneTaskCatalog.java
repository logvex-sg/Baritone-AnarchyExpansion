package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses a Baritone job request into a validated, self-describing record.
 *
 * <p>The addon drives Baritone's processes (mine, follow, explore, get-to-block, farm, build) but the
 * request itself is just text: a chat line, a terminal command or a scheduled task. Turning that text into
 * a typed {@link Request} - and deciding what is a valid request at all - is pure, so the parsing and the
 * rejection rules are covered headlessly instead of only being felt when a bot is already flying.
 *
 * <p>The catalog is deliberately strict. A malformed request is reported as {@link Request#valid()} == false
 * with a reason, so a typo stops at the command line rather than being handed to Baritone as a job that
 * silently does nothing.
 */
public final class BaritoneTaskCatalog {
    /** What kind of Baritone job a request asks for. */
    public enum Kind {
        MINE,
        GOTO,
        FOLLOW,
        EXPLORE,
        FARM,
        TUNNEL,
        BUILD,
        STOP
    }

    /**
     * A parsed request.
     *
     * @param kind     which process to drive
     * @param target   block id, player name or schematic file, depending on the kind
     * @param quantity how many to mine; 0 means "as many as the process finds"
     * @param x        destination / origin x (also the explore centre)
     * @param y        destination / origin y (ignored by explore)
     * @param z        destination / origin z (also the explore centre)
     * @param valid    false when the request was malformed
     * @param error    why the request was rejected, empty when valid
     */
    public record Request(Kind kind, String target, int quantity, int x, int y, int z,
                          boolean valid, String error) {

        public static Request of(Kind kind, String target, int quantity, int x, int y, int z) {
            return new Request(kind, target == null ? "" : target, quantity, x, y, z, true, "");
        }

        public static Request invalid(String error) {
            return new Request(null, "", 0, 0, 0, 0, false, error);
        }

        /** A one-line description for chat, the HUD and the task queue. */
        public String describe() {
            if (!valid) return "invalid: " + error;
            return switch (kind) {
                case MINE -> quantity > 0
                    ? "mine %d x %s".formatted(quantity, target)
                    : "mine " + target;
                case GOTO -> "goto %d %d %d".formatted(x, y, z);
                case FOLLOW -> "follow " + target;
                case EXPLORE -> "explore around %d, %d".formatted(x, z);
                case FARM -> quantity > 0 ? "farm within " + quantity + " blocks" : "farm nearby";
                case TUNNEL -> "tunnel";
                case BUILD -> "build %s at %d %d %d".formatted(target, x, y, z);
                case STOP -> "stop";
            };
        }
    }

    private static final int MAX_QUANTITY = 65_536;
    private static final int MAX_COORDINATE = 30_000_000;

    private BaritoneTaskCatalog() {
    }

    /**
     * Parses a request from its arguments (the subcommand first, e.g. {@code ["mine", "diamond_ore",
     * "64"]}). Returns an invalid request rather than throwing on anything malformed.
     */
    public static Request parse(List<String> args) {
        if (args == null || args.isEmpty()) return Request.invalid("no task given");

        String verb = args.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(1, args.size());

        return switch (verb) {
            case "mine" -> mine(rest);
            case "goto", "go" -> gotoPos(rest);
            case "follow" -> follow(rest);
            case "explore" -> explore(rest);
            case "farm" -> farm(rest);
            case "tunnel" -> Request.of(Kind.TUNNEL, "", 0, 0, 0, 0);
            case "build" -> build(rest);
            case "stop", "cancel" -> Request.of(Kind.STOP, "", 0, 0, 0, 0);
            default -> Request.invalid("unknown task '" + verb + "'");
        };
    }

    /** Splits a raw line on whitespace and parses it. */
    public static Request parseLine(String line) {
        if (line == null || line.isBlank()) return Request.invalid("no task given");
        return parse(new ArrayList<>(List.of(line.trim().split("\\s+"))));
    }

    private static Request mine(List<String> rest) {
        if (rest.isEmpty()) return Request.invalid("mine needs a block or item name");

        // Tolerate "mine 64 diamond_ore" as well as "mine diamond_ore 64".
        String first = rest.get(0);
        int quantity = 0;
        String target;

        if (isInteger(first) && rest.size() >= 2) {
            quantity = parseQuantity(first);
            target = rest.get(1);
        } else {
            target = first;
            if (rest.size() >= 2) {
                if (!isInteger(rest.get(1))) return Request.invalid("mine quantity must be a number");
                quantity = parseQuantity(rest.get(1));
            }
        }

        if (target.isBlank()) return Request.invalid("mine needs a block or item name");
        if (quantity < 0) return Request.invalid("mine quantity must not be negative");

        return Request.of(Kind.MINE, normalizeName(target), quantity, 0, 0, 0);
    }

    private static Request gotoPos(List<String> rest) {
        if (rest.size() < 3) return Request.invalid("goto needs x y z");

        Integer x = parseCoordinate(rest.get(0));
        Integer y = parseCoordinate(rest.get(1));
        Integer z = parseCoordinate(rest.get(2));

        if (x == null || y == null || z == null) return Request.invalid("goto coordinates must be numbers");

        return Request.of(Kind.GOTO, "", 0, x, y, z);
    }

    private static Request follow(List<String> rest) {
        if (rest.isEmpty() || rest.get(0).isBlank()) return Request.invalid("follow needs a player name");
        return Request.of(Kind.FOLLOW, rest.get(0), 0, 0, 0, 0);
    }

    private static Request explore(List<String> rest) {
        if (rest.isEmpty()) return Request.of(Kind.EXPLORE, "", 0, 0, 0, 0);
        if (rest.size() < 2) return Request.invalid("explore needs both an x and a z, or neither");

        Integer x = parseCoordinate(rest.get(0));
        Integer z = parseCoordinate(rest.get(1));
        if (x == null || z == null) return Request.invalid("explore coordinates must be numbers");

        return Request.of(Kind.EXPLORE, "", 0, x, 0, z);
    }

    private static Request farm(List<String> rest) {
        if (rest.isEmpty()) return Request.of(Kind.FARM, "", 0, 0, 0, 0);

        Integer range = parseCoordinate(rest.get(0));
        if (range == null || range < 0) return Request.invalid("farm range must be a non-negative number");

        return Request.of(Kind.FARM, "", range, 0, 0, 0);
    }

    private static Request build(List<String> rest) {
        if (rest.size() < 4) return Request.invalid("build needs a schematic file and x y z");

        String file = rest.get(0);
        if (file.isBlank()) return Request.invalid("build needs a schematic file name");

        Integer x = parseCoordinate(rest.get(1));
        Integer y = parseCoordinate(rest.get(2));
        Integer z = parseCoordinate(rest.get(3));
        if (x == null || y == null || z == null) return Request.invalid("build origin must be numbers");

        return Request.of(Kind.BUILD, file, 0, x, y, z);
    }

    private static int parseQuantity(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > MAX_QUANTITY) return MAX_QUANTITY;
            return parsed;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Integer parseCoordinate(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < -MAX_COORDINATE || parsed > MAX_COORDINATE) return null;
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Strips a namespace so {@code minecraft:diamond_ore} and {@code diamond_ore} are the same block. */
    public static String normalizeName(String value) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(colon + 1);
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) return false;
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
