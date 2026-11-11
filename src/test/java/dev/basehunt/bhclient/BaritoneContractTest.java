package dev.basehunt.bhclient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies the Baritone API surface this addon drives still exists.
 *
 * <p>Baritone is a compile-only dependency, so a renamed interface, method or setting would still compile
 * here (the call sites are plain Java) and only blow up at runtime, on a user's machine, mid-flight. This
 * reads the class files straight out of the real Baritone jar and asserts the members are present, so the
 * contract is checked without loading Baritone or starting a client.
 *
 * <p>It also guards the specific elytra fields {@code BaritoneHelper} touches: Baritone bundles its elytra
 * pathfinder as a nested jar, and a build where the API changed shape is exactly the kind of failure that
 * must be caught before release.
 */
public class BaritoneContractTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        String jarPath = args[0];

        try (ZipFile zip = new ZipFile(jarPath)) {
            System.out.println("== Baritone API entry points ==");

            // Classes must exist as entries; the helper probes for exactly this class name.
            check("baritone.api.BaritoneAPI present", has(zip, "baritone/api/BaritoneAPI.class"), "missing");
            check("baritone.api.IBaritone present", has(zip, "baritone/api/IBaritone.class"), "missing");
            check("baritone.api.IBaritoneProvider present", has(zip, "baritone/api/IBaritoneProvider.class"), "missing");
            check("baritone.api.Settings present", has(zip, "baritone/api/Settings.class"), "missing");
            check("baritone.api.pathing.goals.GoalNear present",
                has(zip, "baritone/api/pathing/goals/GoalNear.class"), "missing");

            byte[] api = read(zip, "baritone/api/BaritoneAPI.class");
            check("BaritoneAPI.getProvider present", containsUtf8(api, "getProvider"), "missing");
            check("BaritoneAPI.getSettings present", containsUtf8(api, "getSettings"), "missing");

            byte[] provider = read(zip, "baritone/api/IBaritoneProvider.class");
            check("IBaritoneProvider.getPrimaryBaritone present",
                containsUtf8(provider, "getPrimaryBaritone"), "missing");

            System.out.println("== IBaritone processes the helper drives ==");

            byte[] baritone = read(zip, "baritone/api/IBaritone.class");
            check("IBaritone.getPathingBehavior present", containsUtf8(baritone, "getPathingBehavior"), "missing");
            check("IBaritone.getElytraProcess present", containsUtf8(baritone, "getElytraProcess"), "missing");
            check("IBaritone.getCustomGoalProcess present", containsUtf8(baritone, "getCustomGoalProcess"), "missing");
            check("IBaritone.getCommandManager present", containsUtf8(baritone, "getCommandManager"), "missing");

            System.out.println("== IPathingBehavior ==");

            byte[] pathing = read(zip, "baritone/api/behavior/IPathingBehavior.class");
            check("IPathingBehavior.isPathing present", containsUtf8(pathing, "isPathing"), "missing");
            check("IPathingBehavior.cancelEverything present", containsUtf8(pathing, "cancelEverything"), "missing");

            System.out.println("== IElytraProcess (Base Hunter v1 flight) ==");

            byte[] elytra = read(zip, "baritone/api/process/IElytraProcess.class");
            for (String member : new String[] {
                "pathTo", "currentDestination", "resetState", "isLoaded", "isSafeToCancel"
            }) {
                check("IElytraProcess." + member + " present", containsUtf8(elytra, member), "missing");
            }
            // onLostControl is inherited from IBaritoneProcess; the helper calls it through IElytraProcess.
            byte[] process = read(zip, "baritone/api/process/IBaritoneProcess.class");
            check("IBaritoneProcess.onLostControl present", containsUtf8(process, "onLostControl"), "missing");

            System.out.println("== ICustomGoalProcess (on-foot navigation) ==");

            byte[] goal = read(zip, "baritone/api/process/ICustomGoalProcess.class");
            check("ICustomGoalProcess.setGoalAndPath present", containsUtf8(goal, "setGoalAndPath"), "missing");

            System.out.println("== ICommandManager ==");

            byte[] commands = read(zip, "baritone/api/command/manager/ICommandManager.class");
            check("ICommandManager.execute present", containsUtf8(commands, "execute"), "missing");

            System.out.println("== Settings driven by Base Hunter v1 ==");

            byte[] settings = read(zip, "baritone/api/Settings.class");
            // prefix is read for the status display; the elytra* fields are written on enable.
            for (String field : new String[] {
                "prefix", "elytraAutoSwap", "elytraAllowEmergencyLand", "elytraConserveFireworks",
                "elytraAutoJump", "elytraFreeLook", "elytraChatSpam", "elytraTermsAccepted"
            }) {
                check("Settings." + field + " present", containsUtf8(settings, field), "missing");
            }
        }

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static boolean has(ZipFile zip, String path) {
        return zip.getEntry(path) != null;
    }

    static byte[] read(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IllegalStateException("Not found in jar: " + path);

        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * True when the class file's constant pool contains the given name. Field and method names are stored
     * as UTF-8 constants, so their raw bytes appear verbatim in the class file.
     */
    static boolean containsUtf8(byte[] classBytes, String name) {
        byte[] needle = name.getBytes(StandardCharsets.UTF_8);

        outer:
        for (int i = 0; i <= classBytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }

        return false;
    }
}
