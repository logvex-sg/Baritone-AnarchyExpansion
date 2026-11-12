package dev.basehunt.bhclient.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.core.terminal.TerminalBuffer;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.gui.BaseHuntingScreen;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.AutoStorage;
import dev.basehunt.bhclient.modules.AutomationBrain;
import dev.basehunt.bhclient.modules.BaseHunterV2;
import dev.basehunt.bhclient.modules.ChatCommands;
import dev.basehunt.bhclient.modules.CommandTerminal;
import dev.basehunt.bhclient.modules.ChestSorter;
import dev.basehunt.bhclient.modules.EnderChestManager;
import dev.basehunt.bhclient.modules.InventoryManager;
import dev.basehunt.bhclient.modules.KitBot;
import dev.basehunt.bhclient.modules.MendingManager;
import dev.basehunt.bhclient.modules.NewChunks;
import dev.basehunt.bhclient.modules.PlayerLogger;
import dev.basehunt.bhclient.modules.RestockManager;
import dev.basehunt.bhclient.modules.ShulkerManager;
import dev.basehunt.bhclient.modules.SixbtHomeManager;
import dev.basehunt.bhclient.modules.SixbtHotspotTracker;
import dev.basehunt.bhclient.modules.SixbtPlayerTracker;
import dev.basehunt.bhclient.modules.SixbtTpaManager;
import dev.basehunt.bhclient.modules.SixbtTpsMonitor;
import dev.basehunt.bhclient.modules.StorageMonitor;
import dev.basehunt.bhclient.modules.StorageNetwork;
import dev.basehunt.bhclient.modules.SurvivalManager;
import dev.basehunt.bhclient.modules.WebhookNotifier;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.DatabaseIO;
import dev.basehunt.bhclient.systems.EnderChestStore;
import dev.basehunt.bhclient.systems.ExplorationStore;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.systems.WorkflowManager;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

/**
 * Single entry point for everything the addon can do from chat, so no module has to be opened by hand.
 *
 * <p>Commands are grouped by area (explore, stash, player, elytra, route, database) and every group has a
 * status subcommand, so the client can always be inspected without guessing argument shapes.
 */
public class BaseHuntCommand extends Command {
    public BaseHuntCommand() {
        super("basehunt", "Controls the base hunting addon.", "bh");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            status();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("gui").executes(context -> {
            BaseHuntingScreen.open(GuiThemes.get());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("events").executes(context -> {
            recentEvents();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("start").executes(context -> startV2("adaptive")));

        builder.then(literal("stop").executes(context -> {
            BaseHunter hunter = Modules.get().get(BaseHunter.class);
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);

            if (v2 != null && v2.isStarted()) v2.stop();

            if (hunter != null && hunter.isActive()) {
                hunter.disable();
                info("Base Hunter stopped.");
            } else {
                BaritoneHelper.stop();
                info("Baritone pathing cancelled.");
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("pause").executes(context -> {
            BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
            if (hunter != null && hunter.isStarted()) hunter.pause();
            else info("Nothing is flying.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("resume").executes(context -> {
            BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
            if (hunter != null && hunter.isStarted()) hunter.resume();
            else info("Nothing to resume.");
            return SINGLE_SUCCESS;
        }));

        buildExplore(builder);
        buildV2(builder);
        buildFly(builder);
        buildBaritone(builder);
        buildStash(builder);
        buildPlayer(builder);
        buildElytra(builder);
        buildEchest(builder);
        buildInventory(builder);
        buildStorage(builder);
        buildShulker(builder);
        buildWorkflow(builder);
        buildRoute(builder);
        buildDatabase(builder);
        buildWebhook(builder);
        buildTasks(builder);
        buildTransfer(builder);
        buildKit(builder);
        buildTerminal(builder);
    }

    // Groups

    private void buildExplore(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("explore")
            .executes(context -> {
                exploreStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                exploreStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("grid").executes(context -> startV2("grid")))
            .then(literal("spiral").executes(context -> startV2("spiral")))
            .then(literal("adaptive").executes(context -> startV2("adaptive")))
            .then(literal("heading")
                .then(argument("degrees", integer(0, 360)).executes(context -> {
                    startV2("heading");
                    BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                    if (hunter != null) {
                        hunter.setHeadingDegrees(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "degrees"));
                    }
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("custom").executes(context -> {
                BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                if (hunter == null) {
                    error("Base Hunter V2 is unavailable.");
                    return SINGLE_SUCCESS;
                }
                ExplorationStore store = ExplorationStore.get();
                if (store == null || store.customWaypoints().isEmpty()) {
                    error("No route waypoints. Add some with /bh route add first.");
                    return SINGLE_SUCCESS;
                }
                if (!hunter.isActive()) hunter.enable();
                hunter.setExploreMode(BaseHunterV2.ExploreMode.Custom);
                hunter.start();
                info("Exploring the custom route (%d waypoints).", store.customWaypoints().size());
                return SINGLE_SUCCESS;
            }))
            .then(literal("reset").executes(context -> {
                ExplorationStore store = ExplorationStore.get();
                if (store != null) store.clearExplored();
                BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                if (hunter != null) hunter.resetPattern();
                info("Exploration history cleared.");
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildV2(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("v2")
            .executes(context -> {
                v2Status();
                return SINGLE_SUCCESS;
            })
            .then(literal("start").executes(context -> startV2("adaptive")))
            .then(literal("stop").executes(context -> {
                BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                if (hunter != null) hunter.stop();
                return SINGLE_SUCCESS;
            }))
            .then(literal("pause").executes(context -> {
                BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                if (hunter != null && hunter.isStarted()) hunter.pause();
                return SINGLE_SUCCESS;
            }))
            .then(literal("resume").executes(context -> {
                BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                if (hunter != null && hunter.isStarted()) hunter.resume();
                return SINGLE_SUCCESS;
            }))
            .then(literal("refresh").executes(context -> {
                BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
                if (hunter != null) hunter.refreshTargets();
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildFly(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("fly")
            .then(argument("x", integer())
                .then(argument("z", integer())
                    .executes(context -> {
                        flyTo(getInteger(context, "x"), getInteger(context, "z"));
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );
    }

    private void buildBaritone(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("baritone")
            .executes(context -> {
                baritoneStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                baritoneStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("terms").executes(context -> {
                BaseHunter hunter = Modules.get().get(BaseHunter.class);
                if (hunter == null) {
                    info("Base Hunter is not registered.");
                } else if (!BaritoneHelper.usable()) {
                    error("Baritone is not usable: %s", BaritoneHelper.health());
                } else {
                    hunter.acceptTerms();
                }
                return SINGLE_SUCCESS;
            }))
            .then(literal("stop").executes(context -> {
                BaritoneHelper.stop();
                info("Baritone pathing cancelled.");
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void baritoneStatus() {
        info("Baritone: (highlight)%s", BaritoneHelper.AVAILABLE ? "installed" : "not installed");
        if (!BaritoneHelper.AVAILABLE) {
            info("  Base Hunter v1 needs Baritone. v2 flies without it.");
            return;
        }

        String health = BaritoneHelper.health();
        if (!health.isEmpty()) {
            error("  Unusable: %s", health);
            return;
        }

        info("  Version: (highlight)%s", BaritoneHelper.version());
        info("  Primary instance: (highlight)%s", BaritoneHelper.get() != null ? "ready" : "missing");
        info("  Pathing: %s, elytra flight: %s",
            BaritoneHelper.isPathing() ? "yes" : "no",
            BaritoneHelper.isElytraLoaded() ? "yes" : "no");
    }

    private void buildStash(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("stash")
            .then(literal("list").executes(context -> {
                stashList();
                return SINGLE_SUCCESS;
            }))
            .then(literal("nearby").executes(context -> {
                stashNearby();
                return SINGLE_SUCCESS;
            }))
            .then(literal("search")
                .then(argument("query", StringArgumentType.greedyString())
                    .executes(context -> {
                        stashSearch(StringArgumentType.getString(context, "query"));
                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("export").executes(context -> {
                if (DatabaseIO.exportToFile()) info("Exported to %s.", DatabaseIO.exportFile().getName());
                else error("Export failed.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                StashManager manager = StashManager.get();
                if (manager != null) {
                    int removed = manager.removeWhere(stash -> stash.server.equals(ServerUtils.serverAddress()));
                    info("Removed %d stash record(s).", removed);
                }
                return SINGLE_SUCCESS;
            }))
        );

        // Kept as an alias so the older spelling still works.
        builder.then(literal("stashes").executes(context -> {
            stashList();
            return SINGLE_SUCCESS;
        }));
    }

    private void buildPlayer(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("player")
            .executes(context -> {
                playerList();
                return SINGLE_SUCCESS;
            })
            .then(literal("list").executes(context -> {
                playerList();
                return SINGLE_SUCCESS;
            }))
            .then(literal("search")
                .then(argument("query", StringArgumentType.greedyString())
                    .executes(context -> {
                        playerSearch(StringArgumentType.getString(context, "query"));
                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("history")
                .then(argument("query", StringArgumentType.greedyString())
                    .executes(context -> {
                        playerHistory(StringArgumentType.getString(context, "query"));
                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("expire")
                .then(argument("hours", integer(1))
                    .executes(context -> {
                        PlayerTracker tracker = PlayerTracker.get();
                        if (tracker != null) {
                            int removed = tracker.expireOlderThan(getInteger(context, "hours"));
                            info("Removed %d player record(s).", removed);
                        }
                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("clear").executes(context -> {
                PlayerTracker tracker = PlayerTracker.get();
                if (tracker != null) info("Removed %d player records.", tracker.clearCurrentServer());
                PlayerLogger logger = Modules.get().get(PlayerLogger.class);
                if (logger != null) logger.clearSpots();
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("players").executes(context -> {
            playerList();
            return SINGLE_SUCCESS;
        }));
    }

    private void buildElytra(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("elytra")
            .executes(context -> {
                elytraStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                elytraStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("replace").executes(context -> {
                if (ElytraManager.equipBest()) info("Equipped a fresh elytra: %s", ElytraManager.status());
                else error("No usable spare elytra found.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("repair")
                .then(literal("status").executes(context -> {
                    MendingManager mending = Modules.get().get(MendingManager.class);
                    if (mending == null) {
                        error("Mending manager is unavailable.");
                        return SINGLE_SUCCESS;
                    }
                    info("Mending: %s", mending.statusLine());
                    return SINGLE_SUCCESS;
                }))
            )
        );
    }

    private void buildEchest(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("echest")
            .executes(context -> {
                echestStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                echestStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("open").executes(context -> {
                EnderChestManager manager = Modules.get().get(EnderChestManager.class);
                if (manager == null || !manager.isActive()) {
                    error("Enable the ender-chest-manager module first.");
                    return SINGLE_SUCCESS;
                }

                if (manager.openLookingAt()) info("Opening the ender chest you are looking at.");
                else error("Could not open: %s.", manager.lastFailure());
                return SINGLE_SUCCESS;
            }))
            .then(literal("organise").executes(context -> {
                EnderChestManager manager = Modules.get().get(EnderChestManager.class);
                if (manager == null || !manager.isActive()) {
                    error("Enable the ender-chest-manager module first.");
                    return SINGLE_SUCCESS;
                }

                if (!manager.organise()) error("Could not organise: %s.", manager.lastFailure());
                return SINGLE_SUCCESS;
            }))
            .then(literal("goto").executes(context -> {
                EnderChestManager manager = Modules.get().get(EnderChestManager.class);
                if (manager == null || !manager.isActive()) {
                    error("Enable the ender-chest-manager module first.");
                    return SINGLE_SUCCESS;
                }

                String failure = manager.navigateToNearest();
                if (failure != null) error("Could not navigate: %s.", failure);
                else info("Heading to the nearest recorded ender chest.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("list").executes(context -> {
                EnderChestStore store = EnderChestStore.get();
                if (store == null) {
                    error("Ender chest store is not loaded.");
                    return SINGLE_SUCCESS;
                }

                var chests = store.forCurrentServer();
                if (chests.isEmpty()) {
                    info("No ender chests recorded for this server.");
                    return SINGLE_SUCCESS;
                }

                info("Ender chests on %s:", ServerUtils.serverAddress());
                for (EnderChestStore.Chest chest : chests) {
                    info("  %s (%s) opened %d time(s)", chest.pos.toShortString(), chest.dimension, chest.timesOpened);
                }
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildInventory(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("inventory")
            .executes(context -> {
                inventoryStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                inventoryStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("organise").executes(context -> {
                EnderChestManager manager = Modules.get().get(EnderChestManager.class);
                if (manager != null && manager.organise()) info("Organising the open ender chest.");
                else error("No ender chest is open, or the manager is disabled.");
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildStorage(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("storage")
            .executes(context -> {
                storageStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                storageStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("list").executes(context -> {
                storageList();
                return SINGLE_SUCCESS;
            }))
            .then(literal("register").executes(context -> {
                StorageNetwork network = Modules.get().get(StorageNetwork.class);
                if (network == null) {
                    error("The storage network module is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("%s", network.registerLooking());
                return SINGLE_SUCCESS;
            }))
            .then(literal("goto")
                .then(argument("id", integer(1)).executes(context -> {
                    StorageNetwork network = Modules.get().get(StorageNetwork.class);
                    if (network == null) {
                        error("The storage network module is not loaded.");
                        return SINGLE_SUCCESS;
                    }
                    info("%s", network.gotoContainer(getInteger(context, "id")));
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("sort").executes(context -> {
                InventoryManager inventory = Modules.get().get(InventoryManager.class);
                if (inventory == null) {
                    error("The inventory manager module is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("%s", inventory.sortNow("command"));
                return SINGLE_SUCCESS;
            }))
            .then(literal("deposit").executes(context -> {
                ChestSorter chest = Modules.get().get(ChestSorter.class);
                if (chest == null || !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)) {
                    error("Open a container first.");
                    return SINGLE_SUCCESS;
                }
                info("%s", chest.deposit(screen.getScreenHandler()));
                return SINGLE_SUCCESS;
            }))
            .then(literal("withdraw").executes(context -> {
                ChestSorter chest = Modules.get().get(ChestSorter.class);
                if (chest == null || !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)) {
                    error("Open a container first.");
                    return SINGLE_SUCCESS;
                }
                info("%s", chest.withdraw(screen.getScreenHandler()));
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                StorageRegistry registry = StorageRegistry.get();
                if (registry == null) {
                    error("The storage registry is not loaded.");
                    return SINGLE_SUCCESS;
                }
                int removed = registry.removeWhere(container ->
                    container.server.equals(ServerUtils.serverAddress()));
                info("Removed %d container(s) on this server.", removed);
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildShulker(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("shulker")
            .executes(context -> {
                shulkerStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                shulkerStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("search")
                .then(argument("item", StringArgumentType.word()).executes(context -> {
                    ShulkerManager shulker = Modules.get().get(ShulkerManager.class);
                    if (shulker == null) {
                        error("The shulker manager module is not loaded.");
                        return SINGLE_SUCCESS;
                    }
                    info("%s", shulker.search(StringArgumentType.getString(context, "item")));
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("fill").executes(context -> {
                ShulkerManager shulker = Modules.get().get(ShulkerManager.class);
                if (shulker == null || !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)
                    || !(screen.getScreenHandler() instanceof net.minecraft.screen.ShulkerBoxScreenHandler handler)) {
                    error("Open a shulker box first.");
                    return SINGLE_SUCCESS;
                }
                info("%s", shulker.fill(handler));
                return SINGLE_SUCCESS;
            }))
            .then(literal("empty").executes(context -> {
                ShulkerManager shulker = Modules.get().get(ShulkerManager.class);
                if (shulker == null || !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)
                    || !(screen.getScreenHandler() instanceof net.minecraft.screen.ShulkerBoxScreenHandler handler)) {
                    error("Open a shulker box first.");
                    return SINGLE_SUCCESS;
                }
                info("%s", shulker.empty(handler));
                return SINGLE_SUCCESS;
            }))
            .then(literal("sort").executes(context -> {
                ShulkerManager shulker = Modules.get().get(ShulkerManager.class);
                if (shulker == null || !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)
                    || !(screen.getScreenHandler() instanceof net.minecraft.screen.ShulkerBoxScreenHandler handler)) {
                    error("Open a shulker box first.");
                    return SINGLE_SUCCESS;
                }
                info("%s", shulker.sortShulker(handler));
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildWorkflow(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("workflow")
            .executes(context -> {
                workflowStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                workflowStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("list").executes(context -> {
                WorkflowManager manager = WorkflowManager.get();
                if (manager == null) {
                    error("The workflow store is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("Workflows (%d):", manager.all().size());
                for (var workflow : manager.all()) {
                    info("  %s (%d steps)", workflow.name(), workflow.steps().size());
                }
                return SINGLE_SUCCESS;
            }))
            .then(literal("run")
                .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                    StorageMonitor monitor = Modules.get().get(StorageMonitor.class);
                    if (monitor == null) {
                        error("The storage monitor module is not loaded.");
                        return SINGLE_SUCCESS;
                    }
                    info("%s", monitor.start(StringArgumentType.getString(context, "name")));
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("stop").executes(context -> {
                StorageMonitor monitor = Modules.get().get(StorageMonitor.class);
                if (monitor != null) monitor.stop();
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("restock")
            .executes(context -> {
                restockPreview();
                return SINGLE_SUCCESS;
            })
            .then(literal("preview").executes(context -> {
                restockPreview();
                return SINGLE_SUCCESS;
            }))
            .then(literal("start").executes(context -> {
                RestockManager restock = Modules.get().get(RestockManager.class);
                if (restock == null) {
                    error("The restock manager module is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("%s", restock.start());
                return SINGLE_SUCCESS;
            }))
            .then(literal("stop").executes(context -> {
                RestockManager restock = Modules.get().get(RestockManager.class);
                if (restock != null) restock.stop();
                info("Restock stopped.");
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildRoute(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("route")
            .executes(context -> {
                routeList();
                return SINGLE_SUCCESS;
            })
            .then(literal("list").executes(context -> {
                routeList();
                return SINGLE_SUCCESS;
            }))
            .then(literal("add").executes(context -> {
                if (mc.player == null) return SINGLE_SUCCESS;
                ExplorationStore store = ExplorationStore.get();
                if (store == null) {
                    error("Exploration store is not loaded.");
                    return SINGLE_SUCCESS;
                }

                store.addWaypoint("waypoint-%d".formatted(store.customWaypoints().size() + 1),
                    mc.player.getBlockPos(), ServerUtils.dimension());
                info("Route waypoint added at %s. %d total.", ServerUtils.coordinates(), store.customWaypoints().size());
                return SINGLE_SUCCESS;
            }))
            .then(literal("add-at")
                .then(argument("x", integer())
                    .then(argument("z", integer())
                        .executes(context -> {
                            ExplorationStore store = ExplorationStore.get();
                            if (store == null) return SINGLE_SUCCESS;

                            store.addWaypoint("waypoint-%d".formatted(store.customWaypoints().size() + 1),
                                new BlockPos(getInteger(context, "x"), 64, getInteger(context, "z")), ServerUtils.dimension());
                            info("Route waypoint added. %d total.", store.customWaypoints().size());
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
            .then(literal("clear").executes(context -> {
                ExplorationStore store = ExplorationStore.get();
                if (store != null) store.clearWaypoints();
                info("Route waypoints cleared.");
                return SINGLE_SUCCESS;
            }))
        );

        // The original v1 waypoint commands are kept so existing muscle memory still works.
        builder.then(literal("waypoint")
            .then(literal("add").executes(context -> {
                BaseHunter hunter = Modules.get().get(BaseHunter.class);
                if (hunter == null || mc.player == null) return SINGLE_SUCCESS;

                hunter.addWaypoint(mc.player.getBlockPos());
                info("Waypoint added at %s. %d total.", ServerUtils.coordinates(), hunter.getWaypointCount());
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                BaseHunter hunter = Modules.get().get(BaseHunter.class);
                if (hunter != null) hunter.clearWaypoints();
                info("Waypoints cleared.");
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildDatabase(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("database")
            .executes(context -> {
                databaseStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                databaseStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("export").executes(context -> {
                if (DatabaseIO.exportToFile()) info("Exported to %s.", DatabaseIO.exportFile().getAbsolutePath());
                else error("Export failed, check the log.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("import").executes(context -> {
                int applied = DatabaseIO.importFromFile();
                if (applied == 0) error("Nothing imported. Is there an export file?");
                else info("Imported %d section(s).", applied);
                return SINGLE_SUCCESS;
            }))
            .then(literal("copy").executes(context -> {
                if (DatabaseIO.exportToClipboard()) info("Copied the database to the clipboard.");
                else error("Copy failed.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("paste").executes(context -> {
                int applied = DatabaseIO.importFromClipboard();
                if (applied == 0) error("Nothing imported from the clipboard.");
                else info("Imported %d section(s).", applied);
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildWebhook(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("webhook")
            .then(literal("test").executes(context -> {
                WebhookNotifier notifier = Modules.get().get(WebhookNotifier.class);
                if (notifier == null || !notifier.isActive()) {
                    error("Enable the webhook-notifier module first.");
                    return SINGLE_SUCCESS;
                }

                info("Sending a test webhook.");
                notifier.sendTest();
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildTasks(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("tasks")
            .executes(context -> {
                tasksStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                tasksStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("list").executes(context -> {
                tasksStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("cancel")
                .then(argument("id", integer(1)).executes(context -> {
                    AutomationEngine engine = AutomationEngine.get();
                    if (engine == null) {
                        error("The automation engine is not running.");
                        return SINGLE_SUCCESS;
                    }
                    int id = getInteger(context, "id");
                    if (engine.cancel(id)) info("Cancelled task #%d.", id);
                    else error("No active or queued task #%d.", id);
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("cancelall").executes(context -> {
                AutomationEngine engine = AutomationEngine.get();
                if (engine == null) {
                    error("The automation engine is not running.");
                    return SINGLE_SUCCESS;
                }
                int count = engine.cancelAll("cancelled by command");
                info("Cancelled %d task(s).", count);
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                AutomationEngine engine = AutomationEngine.get();
                if (engine == null) {
                    error("The automation engine is not running.");
                    return SINGLE_SUCCESS;
                }
                engine.queue().clearHistory();
                info("Cleared the task history.");
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("automation")
            .executes(context -> {
                automationStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                automationStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("reactions").executes(context -> {
                AutomationBrain brain = Modules.get().get(AutomationBrain.class);
                if (brain == null) {
                    error("The automation brain is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("Reactions:");
                for (String line : brain.recent()) info("  %s", line);
                return SINGLE_SUCCESS;
            }))
            .then(literal("start").executes(context -> {
                AutomationBrain brain = Modules.get().get(AutomationBrain.class);
                if (brain == null) {
                    error("The automation brain is not loaded.");
                    return SINGLE_SUCCESS;
                }
                if (!brain.isActive()) brain.enable();
                info("Automation brain enabled.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("stop").executes(context -> {
                AutomationBrain brain = Modules.get().get(AutomationBrain.class);
                if (brain != null && brain.isActive()) brain.disable();
                info("Automation brain disabled.");
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void buildTransfer(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("withdraw")
            .then(argument("item", StringArgumentType.word())
                .executes(context -> withdraw(StringArgumentType.getString(context, "item"), 0))
                .then(argument("amount", integer(1)).executes(context -> withdraw(
                    StringArgumentType.getString(context, "item"), getInteger(context, "amount"))))
            )
        );

        builder.then(literal("deposit")
            .then(argument("item", StringArgumentType.word())
                .executes(context -> deposit(StringArgumentType.getString(context, "item"), 0))
                .then(argument("amount", integer(1)).executes(context -> deposit(
                    StringArgumentType.getString(context, "item"), getInteger(context, "amount"))))
            )
        );

        builder.then(literal("grab").executes(context -> {
            // A convenience: withdraw the whole first argument, all of it.
            error("Usage: /bh withdraw <item> [amount]");
            return SINGLE_SUCCESS;
        }));
    }

    private void buildKit(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("kit")
            .executes(context -> {
                kitStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                kitStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("list").executes(context -> {
                kitStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("claim")
                .then(argument("name", StringArgumentType.word()).executes(context -> {
                    KitBot bot = Modules.get().get(KitBot.class);
                    if (bot == null) {
                        error("The kit bot is not loaded.");
                        return SINGLE_SUCCESS;
                    }
                    info("%s", bot.claim(StringArgumentType.getString(context, "name")));
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("all").executes(context -> {
                KitBot bot = Modules.get().get(KitBot.class);
                if (bot == null) {
                    error("The kit bot is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("%s", bot.claimAll());
                return SINGLE_SUCCESS;
            }))
            .then(literal("delivery").executes(context -> {
                kitDeliveryStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("deliver")
                .then(argument("player", StringArgumentType.word())
                    .then(argument("kit", StringArgumentType.word()).executes(context -> {
                        KitBot bot = Modules.get().get(KitBot.class);
                        if (bot == null) {
                            error("The kit bot is not loaded.");
                            return SINGLE_SUCCESS;
                        }
                        info("%s", bot.deliverTo(StringArgumentType.getString(context, "player"),
                            StringArgumentType.getString(context, "kit")));
                        return SINGLE_SUCCESS;
                    })))
            )
            .then(literal("queue")
                .executes(context -> {
                    kitDeliveryStatus();
                    return SINGLE_SUCCESS;
                })
                .then(literal("clear").executes(context -> {
                    KitBot bot = Modules.get().get(KitBot.class);
                    if (bot != null) info("%s", bot.clearQueue());
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("loadout")
                .executes(context -> {
                    kitLoadoutList();
                    return SINGLE_SUCCESS;
                })
                .then(literal("list").executes(context -> {
                    kitLoadoutList();
                    return SINGLE_SUCCESS;
                }))
                .then(literal("show")
                    .then(argument("kit", StringArgumentType.word()).executes(context -> {
                        KitBot bot = Modules.get().get(KitBot.class);
                        if (bot == null) return SINGLE_SUCCESS;
                        for (String line : bot.loadouts().findOrDefault(StringArgumentType.getString(context, "kit"))
                            .describe()) {
                            info("%s", line);
                        }
                        return SINGLE_SUCCESS;
                    }))
                )
                .then(literal("add")
                    .then(argument("kit", StringArgumentType.word())
                        .then(argument("item", StringArgumentType.word()).executes(context -> {
                            KitBot bot = Modules.get().get(KitBot.class);
                            if (bot != null) {
                                info("%s", bot.addLoadoutItem(StringArgumentType.getString(context, "kit"),
                                    StringArgumentType.getString(context, "item"), 1));
                            }
                            return SINGLE_SUCCESS;
                        })
                            .then(argument("count", integer(1, 2304)).executes(context -> {
                                KitBot bot = Modules.get().get(KitBot.class);
                                if (bot != null) {
                                    info("%s", bot.addLoadoutItem(StringArgumentType.getString(context, "kit"),
                                        StringArgumentType.getString(context, "item"), getInteger(context, "count")));
                                }
                                return SINGLE_SUCCESS;
                            }))))
                )
                .then(literal("remove")
                    .then(argument("kit", StringArgumentType.word())
                        .then(argument("item", StringArgumentType.word()).executes(context -> {
                            KitBot bot = Modules.get().get(KitBot.class);
                            if (bot != null) {
                                info("%s", bot.removeLoadoutItem(StringArgumentType.getString(context, "kit"),
                                    StringArgumentType.getString(context, "item")));
                            }
                            return SINGLE_SUCCESS;
                        })))
                )
                .then(literal("create")
                    .then(argument("kit", StringArgumentType.word()).executes(context -> {
                        KitBot bot = Modules.get().get(KitBot.class);
                        if (bot != null) info("%s", bot.createLoadout(StringArgumentType.getString(context, "kit")));
                        return SINGLE_SUCCESS;
                    }))
                )
                .then(literal("delete")
                    .then(argument("kit", StringArgumentType.word()).executes(context -> {
                        KitBot bot = Modules.get().get(KitBot.class);
                        if (bot != null) info("%s", bot.deleteLoadout(StringArgumentType.getString(context, "kit")));
                        return SINGLE_SUCCESS;
                    }))
                )
            )
            .then(literal("restock")
                .then(argument("kit", StringArgumentType.word()).executes(context -> {
                    KitBot bot = Modules.get().get(KitBot.class);
                    if (bot == null) return SINGLE_SUCCESS;
                    for (String line : bot.restockFor(StringArgumentType.getString(context, "kit")).describe()) {
                        info("%s", line);
                    }
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("gui").executes(context -> {
                dev.basehunt.bhclient.gui.SixbtMenuScreen.open(GuiThemes.get());
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("kits").executes(context -> {
            kitStatus();
            return SINGLE_SUCCESS;
        }));

        buildSixbt(builder);
    }

    private void buildSixbt(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("6b6t")
            .executes(context -> {
                sixbtStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                sixbtStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("gui").executes(context -> {
                dev.basehunt.bhclient.gui.SixbtMenuScreen.open(GuiThemes.get());
                return SINGLE_SUCCESS;
            }))
            .then(literal("home")
                .executes(context -> {
                    sixbtHomeList();
                    return SINGLE_SUCCESS;
                })
                .then(literal("list").executes(context -> {
                    sixbtHomeList();
                    return SINGLE_SUCCESS;
                }))
                .then(literal("set")
                    .executes(context -> {
                        sixbtHome("set", "");
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("name", StringArgumentType.word()).executes(context -> {
                        sixbtHome("set", StringArgumentType.getString(context, "name"));
                        return SINGLE_SUCCESS;
                    })))
                .then(literal("goto")
                    .then(argument("name", StringArgumentType.word()).executes(context -> {
                        sixbtHome("goto", StringArgumentType.getString(context, "name"));
                        return SINGLE_SUCCESS;
                    })))
                .then(literal("del")
                    .then(argument("name", StringArgumentType.word()).executes(context -> {
                        sixbtHome("del", StringArgumentType.getString(context, "name"));
                        return SINGLE_SUCCESS;
                    })))
                .then(literal("cancel").executes(context -> {
                    sixbtHome("cancel", "");
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("tpa")
                .executes(context -> {
                    sixbtTpa("", "");
                    return SINGLE_SUCCESS;
                })
                .then(literal("send")
                    .then(argument("player", StringArgumentType.word()).executes(context -> {
                        sixbtTpa("send", StringArgumentType.getString(context, "player"));
                        return SINGLE_SUCCESS;
                    })))
                .then(literal("cancel").executes(context -> {
                    sixbtTpa("cancel", "");
                    return SINGLE_SUCCESS;
                }))
                .then(literal("accept")
                    .then(argument("player", StringArgumentType.word()).executes(context -> {
                        sixbtTpa("accept", StringArgumentType.getString(context, "player"));
                        return SINGLE_SUCCESS;
                    })))
                .then(literal("decline")
                    .then(argument("player", StringArgumentType.word()).executes(context -> {
                        sixbtTpa("decline", StringArgumentType.getString(context, "player"));
                        return SINGLE_SUCCESS;
                    })))
            )
            .then(literal("tps")
                .executes(context -> {
                    sixbtTps("");
                    return SINGLE_SUCCESS;
                })
                .then(literal("poll").executes(context -> {
                    sixbtTps("poll");
                    return SINGLE_SUCCESS;
                })))
            .then(literal("hotspot")
                .executes(context -> {
                    sixbtHotspot("");
                    return SINGLE_SUCCESS;
                })
                .then(literal("join").executes(context -> {
                    sixbtHotspot("join");
                    return SINGLE_SUCCESS;
                }))
                .then(literal("create").executes(context -> {
                    sixbtHotspot("create");
                    return SINGLE_SUCCESS;
                }))
                .then(literal("cancel").executes(context -> {
                    sixbtHotspot("cancel");
                    return SINGLE_SUCCESS;
                }))
                .then(literal("info").executes(context -> {
                    sixbtHotspot("info");
                    return SINGLE_SUCCESS;
                })))
            .then(literal("players").executes(context -> {
                SixbtPlayerTracker tracker = Modules.get().get(SixbtPlayerTracker.class);
                if (tracker == null) {
                    error("The player tracker is not loaded.");
                    return SINGLE_SUCCESS;
                }
                tracker.describe().forEach(line -> info("%s", line));
                return SINGLE_SUCCESS;
            }))
            .then(literal("commands")
                .executes(context -> {
                    sixbtCommands("");
                    return SINGLE_SUCCESS;
                })
                .then(argument("filter", StringArgumentType.word()).executes(context -> {
                    sixbtCommands(StringArgumentType.getString(context, "filter"));
                    return SINGLE_SUCCESS;
                })))
        );
    }

    private void sixbtStatus() {
        String address = ServerUtils.serverAddress();
        info("Server: %s (%s)", address,
            dev.basehunt.bhclient.core.sixbt.SixbtCommand.isSixbtServer(address) ? "6b6t" : "not 6b6t");
        info("Position: %s", ServerUtils.coordinates());
        KitBot bot = Modules.get().get(KitBot.class);
        info("Kit delivery: %s", bot == null ? "(not loaded)" : bot.deliveryStatusLine());
        SixbtHomeManager homes = Modules.get().get(SixbtHomeManager.class);
        info("Homes: %s", homes == null ? "(not loaded)" : homes.statusLine());
        SixbtTpaManager tpa = Modules.get().get(SixbtTpaManager.class);
        info("Tpa: %s", tpa == null ? "(not loaded)" : tpa.statusLine());
        SixbtTpsMonitor tps = Modules.get().get(SixbtTpsMonitor.class);
        info("Tps: %s", tps == null ? "(not loaded)" : tps.statusLine());
        SixbtHotspotTracker hotspot = Modules.get().get(SixbtHotspotTracker.class);
        info("Hotspot: %s", hotspot == null ? "(not loaded)" : hotspot.statusLine());
    }

    private void sixbtHomeList() {
        SixbtHomeManager homes = Modules.get().get(SixbtHomeManager.class);
        if (homes == null) {
            error("The home manager is not loaded.");
            return;
        }
        homes.describe().forEach(line -> info("%s", line));
    }

    private void sixbtHome(String action, String name) {
        SixbtHomeManager homes = Modules.get().get(SixbtHomeManager.class);
        if (homes == null) {
            error("The home manager is not loaded.");
            return;
        }
        switch (action) {
            case "set" -> info("%s", homes.setHome(name));
            case "goto" -> info("%s", homes.visitHome(name));
            case "del" -> info("%s", homes.deleteHome(name));
            case "cancel" -> info("%s", homes.cancel());
            default -> sixbtHomeList();
        }
    }

    private void sixbtTpa(String action, String player) {
        SixbtTpaManager tpa = Modules.get().get(SixbtTpaManager.class);
        if (tpa == null) {
            error("The tpa manager is not loaded.");
            return;
        }
        switch (action) {
            case "send" -> info("%s", tpa.request(player));
            case "cancel" -> info("%s", tpa.cancel());
            case "accept" -> info("%s", tpa.accept(player));
            case "decline" -> info("%s", tpa.decline(player));
            default -> tpa.describe().forEach(line -> info("%s", line));
        }
    }

    private void sixbtTps(String action) {
        SixbtTpsMonitor tps = Modules.get().get(SixbtTpsMonitor.class);
        if (tps == null) {
            error("The TPS monitor is not loaded.");
            return;
        }
        if (action.equals("poll")) {
            if (ServerUtils.mc().player != null && ServerUtils.mc().player.networkHandler != null) {
                ServerUtils.mc().player.networkHandler.sendChatMessage("/tps");
            }
            info("Polled /tps.");
            return;
        }
        tps.describe().forEach(line -> info("%s", line));
    }

    private void sixbtHotspot(String action) {
        SixbtHotspotTracker hotspot = Modules.get().get(SixbtHotspotTracker.class);
        if (hotspot == null) {
            error("The hotspot tracker is not loaded.");
            return;
        }
        switch (action) {
            case "join" -> info("%s", hotspot.join());
            case "create" -> info("%s", hotspot.create());
            case "cancel" -> info("%s", hotspot.cancel());
            case "info" -> info("%s", hotspot.requestInfo());
            default -> hotspot.describe().forEach(line -> info("%s", line));
        }
    }

    private void sixbtCommands(String filter) {
        String needle = filter.toLowerCase(java.util.Locale.ROOT);
        for (dev.basehunt.bhclient.core.sixbt.SixbtCommand.Entry entry
            : dev.basehunt.bhclient.core.sixbt.SixbtCommand.all()) {
            if (!needle.isEmpty() && !entry.name().contains(needle)) continue;
            info("/%s - %s", entry.name(), entry.summary());
        }
    }

    private void kitDeliveryStatus() {
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) {
            error("The kit bot is not loaded.");
            return;
        }
        bot.describeDelivery().forEach(line -> info("%s", line));
    }

    private void kitLoadoutList() {
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) {
            error("The kit bot is not loaded.");
            return;
        }
        bot.loadouts().describe().forEach(line -> info("%s", line));
    }

    private void buildTerminal(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("terminal")
            .executes(context -> {
                BaseHuntingScreen.open(GuiThemes.get());
                info("Opened the Base Hunting screen; the Terminal section is at the bottom.");
                return SINGLE_SUCCESS;
            })
            .then(literal("run")
                .then(argument("line", StringArgumentType.greedyString()).executes(context -> {
                    CommandTerminal terminal = Modules.get().get(CommandTerminal.class);
                    if (terminal == null) {
                        error("The command terminal module is not loaded.");
                        return SINGLE_SUCCESS;
                    }
                    String line = StringArgumentType.getString(context, "line");
                    String result = terminal.execute(line);
                    // Mirror the terminal output into chat so a command run from /bh is not silent.
                    for (TerminalBuffer.Line entry : terminal.buffer().tail(12)) {
                        info("%s", entry.text());
                    }
                    info("Result: %s", result);
                    return SINGLE_SUCCESS;
                }))
            )
            .then(literal("help").executes(context -> {
                CommandTerminal terminal = Modules.get().get(CommandTerminal.class);
                if (terminal == null) {
                    error("The command terminal module is not loaded.");
                    return SINGLE_SUCCESS;
                }
                for (String line : terminal.registry().help()) info("%s", line);
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("chat")
            .executes(context -> {
                ChatCommands commands = Modules.get().get(ChatCommands.class);
                if (commands == null) {
                    error("The chat command module is not loaded.");
                    return SINGLE_SUCCESS;
                }
                info("Run a command with <prefix><name> in chat. Type <prefix>help for the list.");
                info("Commands: %s", String.join(", ", commands.registry().names()));
                return SINGLE_SUCCESS;
            })
            .then(literal("run")
                .then(argument("line", StringArgumentType.greedyString()).executes(context -> {
                    ChatCommands commands = Modules.get().get(ChatCommands.class);
                    if (commands == null) {
                        error("The chat command module is not loaded.");
                        return SINGLE_SUCCESS;
                    }
                    String line = StringArgumentType.getString(context, "line");
                    info("Result: %s", commands.runLine(line));
                    return SINGLE_SUCCESS;
                }))
            )
        );
    }

    // Actions

    private int withdraw(String item, int amount) {
        AutoStorage storage = Modules.get().get(AutoStorage.class);
        if (storage == null) {
            error("The auto-storage module is not loaded.");
            return SINGLE_SUCCESS;
        }
        info("%s", storage.withdraw(item, amount));
        return SINGLE_SUCCESS;
    }

    private int deposit(String item, int amount) {
        AutoStorage storage = Modules.get().get(AutoStorage.class);
        if (storage == null) {
            error("The auto-storage module is not loaded.");
            return SINGLE_SUCCESS;
        }
        info("%s", storage.deposit(item, amount));
        return SINGLE_SUCCESS;
    }

    private void tasksStatus() {
        AutomationEngine engine = AutomationEngine.get();
        if (engine == null) {
            error("The automation engine is not running.");
            return;
        }

        info("Automation tasks");
        if (engine.queue().active() != null) info("Active: %s", engine.queue().active().describe());
        else info("Active: none");

        List<Task> queued = engine.queue().queued();
        info("Queued: %d", queued.size());
        for (Task task : queued) info("  %s", task.describe());

        List<Task> history = engine.queue().history();
        if (!history.isEmpty()) {
            info("Recent:");
            for (Task task : history.subList(0, Math.min(5, history.size()))) info("  %s", task.describe());
        }
    }

    private void automationStatus() {
        info("Automation");
        AutomationEngine engine = AutomationEngine.get();
        info("Engine: %s", engine == null ? "unavailable" : engine.statusLine());

        AutomationBrain brain = Modules.get().get(AutomationBrain.class);
        info("Brain: %s", brain == null || !brain.isActive() ? "inactive" : brain.statusLine());

        AutoStorage storage = Modules.get().get(AutoStorage.class);
        info("Auto-storage: %s", storage == null || !storage.isActive() ? "inactive" : storage.statusLine());

        AutomationManager manager = AutomationManager.get();
        info("Control: %s", manager == null ? "unavailable" : manager.controlSummary());
    }

    private void kitStatus() {
        KitBot bot = Modules.get().get(KitBot.class);
        if (bot == null) {
            error("The kit bot is not loaded.");
            return;
        }
        info("Kits");
        for (String line : bot.describe()) info("  %s", line);
    }

    // Actions

    private int startV2(String mode) {
        BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
        if (hunter == null) {
            error("Base Hunter V2 is unavailable.");
            return SINGLE_SUCCESS;
        }

        BaseHunterV2.ExploreMode exploreMode = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "grid" -> BaseHunterV2.ExploreMode.Grid;
            case "spiral" -> BaseHunterV2.ExploreMode.Spiral;
            case "heading" -> BaseHunterV2.ExploreMode.Heading;
            default -> BaseHunterV2.ExploreMode.Adaptive;
        };

        if (!hunter.isActive()) hunter.enable();
        hunter.setExploreMode(exploreMode);
        hunter.start();
        info("Base Hunter V2 started (%s mode).", exploreMode.name().toLowerCase(java.util.Locale.ROOT));
        return SINGLE_SUCCESS;
    }

    private void flyTo(int x, int z) {
        BaseHunter hunter = Modules.get().get(BaseHunter.class);
        if (hunter == null) {
            error("Base Hunter module is unavailable.");
            return;
        }

        if (!hunter.isActive()) hunter.enable();

        if (!BaritoneHelper.AVAILABLE) {
            error("Baritone is not installed, cannot fly.");
            return;
        }

        hunter.start();
        info("Base Hunter started in %s mode.", hunter.getModeName());
        info("Flying to %d, %d.", x, z);
    }

    private void status() {
        info("Base Hunting Client");
        info("Server: %s (%s)", ServerUtils.serverAddress(), ServerUtils.dimension());
        info("Position: %s", ServerUtils.coordinates());
        info("Baritone: %s", BaritoneHelper.AVAILABLE ? "available" : "missing");

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (newChunks != null && newChunks.isActive()) {
            info("New chunks: %d found, %d proven old", newChunks.getTrackedCount(), newChunks.getOldChunkCount());
        }

        StashManager stashManager = StashManager.get();
        if (stashManager != null) {
            info("Stashes: %d on this server", stashManager.forCurrentServer().size());
        }

        PlayerTracker tracker = PlayerTracker.get();
        if (tracker != null) {
            info("Players: %d on this server", tracker.forCurrentServer().size());
        }

        BaseHunter hunter = Modules.get().get(BaseHunter.class);
        if (hunter != null && hunter.isActive()) {
            BlockPos destination = hunter.getCurrentDestination();
            info("Autopilot: %s, destination %s, flown %,d blocks",
                hunter.isPaused() ? "paused" : "flying",
                destination == null ? "none" : destination.toShortString(),
                (long) hunter.getTravelledBlocks());
        }

        BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
        if (v2 != null && v2.isActive()) {
            info("V2: %s, %d queued, flown %,d blocks",
                v2.isPaused() ? "paused" : (v2.isStarted() ? "flying" : "idle"),
                v2.getQueuedCount(),
                (long) v2.getTravelledBlocks());
        }

        info("Elytra: %s", ElytraManager.status());
    }

    private void exploreStatus() {
        ExplorationStore store = ExplorationStore.get();
        if (store == null) {
            error("Exploration store is not loaded.");
            return;
        }

        info("Exploration");
        info("Explored chunks: %,d", store.regions().exploredCount());
        info("Needs another look: %,d", store.regions().reviewCount());
        info("Route waypoints: %d", store.customWaypoints().size());

        BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
        if (v2 != null) {
            info("V2: %s, %s mode, %d queued",
                v2.isStarted() ? (v2.isPaused() ? "paused" : "flying") : "idle",
                v2.getExploreMode().name().toLowerCase(java.util.Locale.ROOT),
                v2.getQueuedCount());
        }
    }

    private void v2Status() {
        BaseHunterV2 hunter = Modules.get().get(BaseHunterV2.class);
        if (hunter == null) {
            error("Base Hunter V2 is unavailable.");
            return;
        }

        info("Base Hunter V2 - firework free elytra sweep");
        info("Enabled: %s, started: %s, paused: %s",
            hunter.isActive() ? "yes" : "no",
            hunter.isStarted() ? "yes" : "no",
            hunter.isPaused() ? "yes" : "no");

        info("Elytra: %s", ElytraManager.status());

        var target = hunter.getCurrentTarget();
        info("Current target: %s", target == null
            ? "none"
            : "%d, %d (%s)".formatted(target.x(), target.z(), target.reason()));

        info("Queued targets: %d", hunter.getQueuedCount());
        info("Targets visited: %d, elytra swaps: %d", hunter.getTargetsVisited(), hunter.getElytraSwaps());
        info("Flown this run: %,d blocks", (long) hunter.getTravelledBlocks());
        info("Baritone: %s", BaritoneHelper.AVAILABLE ? "available (optional, not used for flight)" : "not installed");

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (newChunks != null) {
            info("New chunks detected: %d", newChunks.getTrackedCount());
        }
    }

    private void stashList() {
        StashManager manager = StashManager.get();
        if (manager == null) {
            error("Stash store is not loaded.");
            return;
        }

        var stashes = manager.forCurrentServer();
        if (stashes.isEmpty()) {
            info("No stashes recorded for this server yet.");
            return;
        }

        info("Stashes on %s (%d):", ServerUtils.serverAddress(), stashes.size());
        for (StashManager.Stash stash : stashes) {
            info("  %s %s [%s] score %d", stash.classification, stash.pos.toShortString(), stash.describeCounts(), stash.score());
        }
    }

    private void stashNearby() {
        if (mc.player == null) return;

        StashManager manager = StashManager.get();
        if (manager == null) {
            error("Stash store is not loaded.");
            return;
        }

        BlockPos player = mc.player.getBlockPos();
        String dimension = ServerUtils.dimension();
        int shown = 0;

        for (StashManager.Stash stash : manager.forCurrentServer()) {
            if (!stash.dimension.equals(dimension)) continue;

            double distance = stash.pos.getSquaredDistance(player);
            if (distance > 2048.0 * 2048.0) continue;

            info("  %s %s (%d blocks away, score %d)",
                stash.classification, stash.pos.toShortString(), (int) Math.sqrt(distance), stash.score());
            shown++;
            if (shown >= 20) break;
        }

        if (shown == 0) info("No stashes within 2048 blocks.");
    }

    private void stashSearch(String query) {
        StashManager manager = StashManager.get();
        if (manager == null) {
            error("Stash store is not loaded.");
            return;
        }

        String needle = query.toLowerCase(java.util.Locale.ROOT);
        int shown = 0;

        for (StashManager.Stash stash : manager.forCurrentServer()) {
            if (!stash.classification.toLowerCase(java.util.Locale.ROOT).contains(needle)
                && !stash.describeCounts().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;

            info("  %s %s [%s] score %d", stash.classification, stash.pos.toShortString(), stash.describeCounts(), stash.score());
            shown++;
            if (shown >= 40) break;
        }

        if (shown == 0) info("No stashes matched '%s'.", query);
    }

    private void playerList() {
        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) {
            error("Player tracker is not loaded.");
            return;
        }

        var players = tracker.forCurrentServer();
        if (players.isEmpty()) {
            info("No players recorded for this server yet.");
            return;
        }

        info("Players seen on %s (%d):", ServerUtils.serverAddress(), players.size());
        for (PlayerTracker.Entry entry : players) {
            String logout = entry.hasLogoutSpot() ? "logout " + entry.logoutCoords() : "no logout spot";
            info("  %s - last %s - %s", entry.name, entry.lastCoords(), logout);
        }
    }

    private void playerSearch(String query) {
        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) {
            error("Player tracker is not loaded.");
            return;
        }

        var results = tracker.search(query);
        if (results.isEmpty()) {
            info("No players matched '%s'.", query);
            return;
        }

        info("Matches for '%s' (%d):", query, results.size());
        for (PlayerTracker.Entry entry : results) {
            info("  %s - %d sighting(s) - %s", entry.name, entry.sightings, entry.lastCoords());
        }
    }

    private void playerHistory(String query) {
        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) {
            error("Player tracker is not loaded.");
            return;
        }

        var results = tracker.search(query);
        if (results.isEmpty()) {
            info("No players matched '%s'.", query);
            return;
        }

        PlayerTracker.Entry entry = results.get(0);
        var sightings = tracker.sightingsFor(entry.uuid);

        info("History for %s (%d sighting(s)):", entry.name, sightings.size());
        int shown = 0;
        for (PlayerTracker.Sighting sighting : sightings) {
            info("  %s %s (%s)", sighting.coords(), sighting.dimension, formatAge(sighting.time));
            if (++shown >= 25) break;
        }
    }

    private void elytraStatus() {
        info("Elytra: %s", ElytraManager.status());
        info("Spare elytras: %d, best spare durability: %d",
            ElytraManager.spareCount(), ElytraManager.bestSpareRemaining());
        info("Flight state: %s", ElytraManager.flightState().name().toLowerCase(java.util.Locale.ROOT));

        MendingManager mending = Modules.get().get(MendingManager.class);
        if (mending != null && mending.isActive()) info("Mending: %s", mending.statusLine());
    }

    private void echestStatus() {
        EnderChestManager manager = Modules.get().get(EnderChestManager.class);
        if (manager == null) {
            error("Ender chest manager is unavailable.");
            return;
        }

        info("Ender chest manager: %s", manager.isActive() ? manager.statusLine() : "inactive");

        EnderChestStore store = EnderChestStore.get();
        info("Recorded ender chests on this server: %d", store == null ? 0 : store.forCurrentServer().size());
    }

    private void inventoryStatus() {
        if (mc.player == null) return;

        int free = 0;
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) free++;
        }

        info("Free inventory slots: %d", free);

        SurvivalManager survival = Modules.get().get(SurvivalManager.class);
        if (survival != null && survival.isActive()) info("Survival: %s", survival.statusLine());
    }

    private void storageStatus() {
        StorageRegistry registry = StorageRegistry.get();
        ContainerInteractionManager interaction = ContainerInteractionManager.get();
        StorageMonitor monitor = Modules.get().get(StorageMonitor.class);

        int containers = registry == null ? 0 : registry.forCurrentServer().size();
        int verified = 0;
        int reachable = 0;
        if (registry != null) {
            for (StorageRegistry.Container container : registry.forCurrentServer()) {
                if (container.verified) verified++;
                if (dev.basehunt.bhclient.utils.ContainerHelper.isReachable(container.pos)) reachable++;
            }
        }

        info("Storage network");
        info("Containers: %d on this server (%d observed, %d in reach)", containers, verified, reachable);
        info("Interaction: %s", interaction == null ? "unavailable" : interaction.status());
        info("Queue: %d", interaction == null ? 0 : interaction.queueSize());
        if (monitor != null) info("Workflow: %s", monitor.engine().describe());

        if (registry != null && registry.lastSkippedRecords() > 0) {
            warning("Skipped %d unreadable container record(s) on the last load.", registry.lastSkippedRecords());
        }

        ContainerInteractionManager.Result last = interaction == null ? null : interaction.lastResult();
        if (last != null) {
            info("Last operation: %s %s (%s)",
                last.success() ? "ok" : "failed", last.label(), last.message());
        }
    }

    private void storageList() {
        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) {
            error("The storage registry is not loaded.");
            return;
        }

        var containers = registry.forCurrentServer();
        if (containers.isEmpty()) {
            info("No containers registered on this server. Look at one and run /bh storage register.");
            return;
        }

        info("Registered containers (%d):", containers.size());
        for (StorageRegistry.Container container : containers) {
            String contents = container.verified
                ? "%d stack(s) seen %s".formatted(container.contents().size(), formatAge(container.lastInteraction))
                : "not yet observed";
            info("  %s - %s", container.describe(), contents);
        }
    }

    private void shulkerStatus() {
        info("Shulkers: %s", dev.basehunt.bhclient.systems.ShulkerManager.status());
        for (var box : dev.basehunt.bhclient.systems.ShulkerManager.carried()) {
            info("  slot %d: %s [%s]", box.slotIndex(), box.label(), box.status().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void workflowStatus() {
        StorageMonitor monitor = Modules.get().get(StorageMonitor.class);
        if (monitor == null) {
            error("The storage monitor module is not loaded.");
            return;
        }
        info("Workflow: %s", monitor.engine().describe());
        info("%s", monitor.report());
    }

    private void restockPreview() {
        RestockManager restock = Modules.get().get(RestockManager.class);
        if (restock == null) {
            error("The restock manager module is not loaded.");
            return;
        }
        var lines = restock.preview();
        if (lines.isEmpty()) {
            info("Everything is stocked up.");
            return;
        }
        info("Restock preview:");
        for (String line : lines) info("  %s", line);
    }

    private void routeList() {
        ExplorationStore store = ExplorationStore.get();
        if (store == null) {
            error("Exploration store is not loaded.");
            return;
        }

        var waypoints = store.customWaypoints();
        if (waypoints.isEmpty()) {
            info("No route waypoints set.");
            return;
        }

        info("Route waypoints (%d):", waypoints.size());
        for (int i = 0; i < waypoints.size(); i++) {
            var waypoint = waypoints.get(i);
            info("  %d. %s %s (%s)", i + 1, waypoint.label, waypoint.pos.toShortString(), waypoint.dimension);
        }
    }

    private void recentEvents() {
        AutomationManager manager = AutomationManager.get();
        if (manager == null) {
            error("The automation bus is not running.");
            return;
        }

        var events = manager.bus().recent(20);
        if (events.isEmpty()) {
            info("No automation events recorded yet.");
            return;
        }

        info("Recent automation events (%d):", events.size());
        for (AutomationEvent event : events) {
            info("  [%s] %s %s", event.type().name(), event.source(), describeEventData(event));
        }
    }

    private String describeEventData(AutomationEvent event) {
        if (event.data().isEmpty()) return "";

        StringBuilder builder = new StringBuilder();
        event.data().forEach((key, value) -> {
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private void databaseStatus() {
        StashManager stashes = StashManager.get();
        PlayerTracker players = PlayerTracker.get();
        EnderChestStore chests = EnderChestStore.get();
        ExplorationStore exploration = ExplorationStore.get();

        info("Database");
        info("Stashes: %,d total, %,d on this server",
            stashes == null ? 0 : stashes.getAll().size(),
            stashes == null ? 0 : stashes.forCurrentServer().size());
        info("Players: %,d total, %,d on this server",
            players == null ? 0 : players.getAll().size(),
            players == null ? 0 : players.forCurrentServer().size());
        info("Sightings: %,d", players == null ? 0 : players.allSightings().size());
        info("Ender chests: %,d on this server", chests == null ? 0 : chests.forCurrentServer().size());
        info("Explored chunks: %,d", exploration == null ? 0 : exploration.regions().exploredCount());
        info("Export file: %s", DatabaseIO.exportFileExists() ? DatabaseIO.exportFile().getName() : "none");

        // A skipped count above zero means the file was partly corrupt and those records were dropped, which
        // is worth telling the user about rather than losing silently.
        int skipped = (stashes == null ? 0 : stashes.lastSkippedRecords())
            + (players == null ? 0 : players.lastSkippedRecords())
            + (chests == null ? 0 : chests.lastSkippedRecords())
            + (exploration == null ? 0 : exploration.lastSkippedRecords());

        if (skipped > 0) {
            warning("Skipped %d unreadable record(s) on the last load; the rest of the database loaded fine.", skipped);
        } else {
            info("Integrity: all records readable.");
        }
    }

    private static String formatAge(long timestamp) {
        if (timestamp <= 0) return "unknown";

        long seconds = (java.lang.System.currentTimeMillis() - timestamp) / 1000L;
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }
}
