package dev.basehunt.bhclient.core;

import net.minecraft.client.MinecraftClient;

/**
 * Runs work on the Minecraft client thread.
 *
 * <p>Minecraft's world, player and screen handler are not thread safe. Anything that touches them must run
 * on the client thread, so background work (chunk scans, disk IO, webhook bookkeeping) either stays clear of
 * game state or hands the game-touching part back here.
 */
public final class ClientThread {
    private ClientThread() {
    }

    public static boolean isOnThread() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null || mc.isOnThread();
    }

    /** Runs the task now when already on the client thread, otherwise schedules it for the next tick. */
    public static void run(Runnable task) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        if (mc.isOnThread()) task.run();
        else mc.execute(task);
    }

    /** Schedules the task on the client thread regardless of the calling thread. */
    public static void runLater(Runnable task) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        mc.execute(task);
    }
}
