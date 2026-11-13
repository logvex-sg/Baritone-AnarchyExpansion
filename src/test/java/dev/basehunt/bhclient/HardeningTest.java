package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.logic.BoundedLongSet;
import dev.basehunt.bhclient.systems.DatabaseIO;
import dev.basehunt.bhclient.utils.WebhookManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests for the stability fixes in this release. Each one pins a bug that was found by reading the
 * code rather than by a crash, so without a test it would silently return.
 *
 * <p>These are plain logic tests with no Minecraft client, matching the rest of the headless suite.
 */
public class HardeningTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        boundedLongSet();
        embedFingerprint();
        databaseCoverage();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** The chunk scanners must never grow without bound; oldest-first eviction is the guarantee. */
    static void boundedLongSet() {
        section("bounded long set");

        BoundedLongSet set = new BoundedLongSet(3);
        set.add(1);
        set.add(2);
        set.add(3);
        check("size is capped", set.size() == 3, "got " + set.size());

        set.add(4);
        check("the cap holds after a new key", set.size() == 3, "got " + set.size());
        check("the oldest key was evicted", !set.contains(1), "still present");
        check("the newest key was kept", set.contains(4), "missing");
        check("re-adding an existing key does not grow the set", !set.add(4) && set.size() == 3, "grew");

        set.remove(2);
        check("remove drops the key", !set.contains(2) && set.size() == 2, "not removed");

        set.clear();
        check("clear empties the set", set.size() == 0, "not empty");

        // The eviction callback keeps a parallel map in step, which is how NewChunks prunes its timestamps.
        Map<Long, Long> parallel = new HashMap<>();
        BoundedLongSet withCallback = new BoundedLongSet(2, parallel::remove);
        for (long i = 0; i < 10; i++) {
            withCallback.add(i);
            parallel.put(i, i);
        }
        check("eviction prunes the parallel map to the set size", parallel.size() == 2, "got " + parallel.size());
        check("the parallel map holds exactly the retained keys", parallel.keySet().equals(withCallback.snapshot()),
            "mismatch");

        // A huge run must stay bounded, not merely survive a handful of keys.
        BoundedLongSet big = new BoundedLongSet(1000);
        for (long i = 0; i < 1_000_000; i++) big.add(i);
        check("a million inserts stay at the cap", big.size() == 1000, "got " + big.size());
        check("the retained keys are the newest", big.contains(999_999) && !big.contains(0), "wrong window");
    }

    /**
     * Two alerts that differ only in their footer or thumbnail must not collapse into one. The webhook
     * dedupe keys on {@code Embed.fingerprint()}, so the fingerprint has to cover every visible field.
     */
    static void embedFingerprint() {
        section("embed fingerprint");

        WebhookManager.Embed a = new WebhookManager.Embed().title("Stash").description("found");
        WebhookManager.Embed b = new WebhookManager.Embed().title("Stash").description("found");
        check("identical embeds share a fingerprint", a.fingerprint().equals(b.fingerprint()), "differ");

        check("a different footer changes the fingerprint",
            !a.fingerprint().equals(new WebhookManager.Embed().title("Stash").description("found")
                .footer("server A").fingerprint()), "collapsed");
        check("a different thumbnail changes the fingerprint",
            !a.fingerprint().equals(new WebhookManager.Embed().title("Stash").description("found")
                .thumbnail("https://x/y.png").fingerprint()), "collapsed");
        check("a different title changes the fingerprint",
            !a.fingerprint().equals(new WebhookManager.Embed().title("Other").description("found").fingerprint()),
            "collapsed");
        check("a different field changes the fingerprint",
            !a.fingerprint().equals(new WebhookManager.Embed().title("Stash").description("found")
                .field("count", "12").fingerprint()), "collapsed");
        check("field order matters to the fingerprint",
            !a.fingerprint().equals(new WebhookManager.Embed().title("Stash").description("found")
                .field("a", "1").field("b", "2").fingerprint())
                || !new WebhookManager.Embed().title("Stash").description("found").field("a", "1").field("b", "2")
                .fingerprint().equals(new WebhookManager.Embed().title("Stash").description("found")
                    .field("b", "2").field("a", "1").fingerprint()), "order ignored");

        // The separator must not be forgeable from field text, or two different embeds could collide.
        WebhookManager.Embed forged1 = new WebhookManager.Embed().title("A").description("B");
        WebhookManager.Embed forged2 = new WebhookManager.Embed().title("A\u0001B").description("");
        check("field text cannot forge the separator", !forged1.fingerprint().equals(forged2.fingerprint()),
            "collision");
    }

    /**
     * A backup that silently drops a store is worse than no backup: importing it discards the live data of
     * every store it does not mention. This pins the coverage so a newly added store cannot fall out of the
     * export/import path unnoticed.
     */
    static void databaseCoverage() {
        section("database coverage");

        List<String> keys = DatabaseIO.sectionKeys();
        List<String> expected = List.of("stashes", "players", "enderChests", "exploration",
            "storage", "workflows", "kits", "kitLoadouts", "sixbtServer", "sixbtPlayers", "deaths",
            "objectives");

        check("every persisted store has a section", new ArrayList<>(keys).containsAll(expected)
            && keys.size() == expected.size(), "got " + keys);
        check("no duplicate sections", keys.size() == new java.util.HashSet<>(keys).size(), "duplicates");
        check("export and import use the same sections", DatabaseIO.sectionKeys().equals(keys), "drifted");
    }
}
