package dev.basehunt.bhclient.core.sixbt;

import java.util.List;
import java.util.Locale;

/**
 * The catalogue of commands the 6b6t server actually provides.
 *
 * <p>6b6t is a Minecraft anarchy server at {@code play.6b6t.org} (Bedrock {@code br.6b6t.org:19132}) whose
 * command set is specific enough that hard-coding it here is more honest than guessing. Every entry below
 * was read from the server's own command reference; a command that is not in this list is not a 6b6t command,
 * and the addon should say so rather than silently sending something the server will reject.
 *
 * <p>The catalogue exists so three things share one source of truth: the "is this server 6b6t?" test, the
 * command-aware chat parsing (a chat line that starts with one of these is a command echo, not a player
 * message) and the help the addon prints. Keeping it pure and static means all of that is covered headlessly.
 */
public final class SixbtCommand {

    /** The category a command belongs to, matching the server's own grouping. */
    public enum Category {
        AUTH, CHAT, GROUP_CHAT, HOMES, COSMETICS, HOTSPOT, GAMEPLAY, SERVER, UNKNOWN
    }

    /** One command: its literal name (without the slash), its category and a short description. */
    public record Entry(String name, Category category, String summary) {
    }

    /** The known 6b6t server addresses. */
    public static final List<String> SERVER_ADDRESSES = List.of(
        "play.6b6t.org", "6b6t.org", "www.6b6t.org", "br.6b6t.org"
    );

    private static final List<Entry> COMMANDS = List.of(
        // Auth
        new Entry("unregister", Category.AUTH, "move a cracked account to the premium one"),
        new Entry("register", Category.AUTH, "register a cracked account"),
        new Entry("login", Category.AUTH, "log in to a cracked account"),

        // Chat
        new Entry("msg", Category.CHAT, "private message a player"),
        new Entry("whisper", Category.CHAT, "private message a player"),
        new Entry("w", Category.CHAT, "private message a player"),
        new Entry("reply", Category.CHAT, "reply to the last private message"),
        new Entry("r", Category.CHAT, "reply to the last private message"),
        new Entry("last", Category.CHAT, "message the last player you messaged"),
        new Entry("l", Category.CHAT, "message the last player you messaged"),
        new Entry("ignore", Category.CHAT, "block a player's messages"),
        new Entry("ignorelist", Category.CHAT, "list ignored players"),
        new Entry("togglewhispers", Category.CHAT, "turn private messages on or off"),
        new Entry("togglechat", Category.CHAT, "hide or show chat"),
        new Entry("toggleheads", Category.CHAT, "hide or show player heads in chat"),
        new Entry("togglespam", Category.CHAT, "hide or show spam"),
        new Entry("showspam", Category.CHAT, "hide or show spam"),
        new Entry("toggleranked", Category.CHAT, "hide or show ranked players' chat"),
        new Entry("bots", Category.CHAT, "bot status and per-type chat filters, including kit requests"),
        new Entry("togglebots", Category.CHAT, "hide or show everything from bots"),
        new Entry("connectionmsgs", Category.CHAT, "hide or show join messages"),
        new Entry("deathmsgs", Category.CHAT, "hide or show death messages"),
        new Entry("advancements", Category.CHAT, "choose which advancement messages you see"),
        new Entry("item", Category.CHAT, "show the held item in chat"),

        // Group chat
        new Entry("groupchat", Category.GROUP_CHAT, "open the group chat menu"),
        new Entry("gc", Category.GROUP_CHAT, "group chat commands"),
        new Entry("g", Category.GROUP_CHAT, "send a message to your group"),
        new Entry("all", Category.GROUP_CHAT, "send a message to public chat in chat mode"),

        // Homes and teleport
        new Entry("sethome", Category.HOMES, "create a home here"),
        new Entry("home", Category.HOMES, "teleport to a home"),
        new Entry("homes", Category.HOMES, "list your homes"),
        new Entry("delhome", Category.HOMES, "delete a home"),
        new Entry("homecancel", Category.HOMES, "cancel a home teleport"),
        new Entry("homerename", Category.HOMES, "rename a home"),
        new Entry("tpa", Category.HOMES, "request to teleport to a player"),
        new Entry("tpcancel", Category.HOMES, "cancel a teleport request you sent"),
        new Entry("toggletpa", Category.HOMES, "hide teleport requests"),
        new Entry("tptoggle", Category.HOMES, "hide teleport requests"),
        new Entry("tpt", Category.HOMES, "hide teleport requests"),
        new Entry("tpy", Category.HOMES, "accept a teleport request"),
        new Entry("tpn", Category.HOMES, "decline a teleport request"),

        // Cosmetics
        new Entry("particles", Category.COSMETICS, "customise particle effects"),
        new Entry("hat", Category.COSMETICS, "wear an item on your head"),
        new Entry("invisframe", Category.COSMETICS, "toggle invisibility frames"),
        new Entry("chatcolor", Category.COSMETICS, "change chat message colour"),
        new Entry("cc", Category.COSMETICS, "change chat message colour"),
        new Entry("namecolor", Category.COSMETICS, "change your name colour"),
        new Entry("nc", Category.COSMETICS, "change your name colour"),
        new Entry("prefix", Category.COSMETICS, "choose your rank prefix"),
        new Entry("cigar", Category.COSMETICS, "light a cigar"),
        new Entry("glow", Category.COSMETICS, "give yourself a glow"),
        new Entry("balloons", Category.COSMETICS, "put a balloon on your head"),
        new Entry("sit", Category.COSMETICS, "sit on a block"),
        new Entry("lay", Category.COSMETICS, "lie down"),
        new Entry("skin", Category.COSMETICS, "change your skin"),

        // Hotspots
        new Entry("hotspot", Category.HOTSPOT, "teleport to or manage the current hotspot"),

        // Gameplay
        new Entry("kill", Category.GAMEPLAY, "kill yourself"),
        new Entry("suicide", Category.GAMEPLAY, "kill yourself"),
        new Entry("buildermode", Category.GAMEPLAY, "temporary view-distance boost"),
        new Entry("freecam", Category.GAMEPLAY, "leave your body and fly to scout"),
        new Entry("rubberband", Category.GAMEPLAY, "report a server pull-back"),
        new Entry("pvpmode", Category.GAMEPLAY, "hide or show PvP mode messages"),

        // Server
        new Entry("shop", Category.SERVER, "get the shop link"),
        new Entry("buy", Category.SERVER, "get the shop link"),
        new Entry("legendgift", Category.SERVER, "gift a rank to another player"),
        new Entry("vote", Category.SERVER, "vote count and instructions"),
        new Entry("tps", Category.SERVER, "show server and personal TPS"),
        new Entry("discord", Category.SERVER, "get the Discord invite"),
        new Entry("website", Category.SERVER, "get the website link"),
        new Entry("help", Category.SERVER, "get the help link")
    );

    private SixbtCommand() {
    }

    /** Every known command. */
    public static List<Entry> all() {
        return COMMANDS;
    }

    /** The commands in one category. */
    public static List<Entry> inCategory(Category category) {
        return COMMANDS.stream().filter(entry -> entry.category() == category).toList();
    }

    /** The entry for a command name, or null when it is not a 6b6t command. */
    public static Entry find(String name) {
        if (name == null) return null;
        String needle = strip(name).toLowerCase(Locale.ROOT);
        for (Entry entry : COMMANDS) {
            if (entry.name().equals(needle)) return entry;
        }
        return null;
    }

    /** True when the name is a known 6b6t command. */
    public static boolean isKnown(String name) {
        return find(name) != null;
    }

    /** The category of a command, or {@link Category#UNKNOWN}. */
    public static Category categoryOf(String name) {
        Entry entry = find(name);
        return entry == null ? Category.UNKNOWN : entry.category();
    }

    /** True when the server address looks like 6b6t (either the Java or the Bedrock host). */
    public static boolean isSixbtServer(String address) {
        if (address == null || address.isBlank()) return false;
        String lower = address.toLowerCase(Locale.ROOT);
        for (String known : SERVER_ADDRESSES) {
            if (lower.equals(known) || lower.endsWith("." + known)) return true;
        }
        return false;
    }

    /** Strips a leading slash so {@code /home base} and {@code home base} both resolve. */
    public static String strip(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    /** The first token of a chat line, without its leading slash. */
    public static String firstToken(String line) {
        if (line == null) return "";
        String stripped = strip(line);
        int space = stripped.indexOf(' ');
        return space < 0 ? stripped : stripped.substring(0, space);
    }
}
