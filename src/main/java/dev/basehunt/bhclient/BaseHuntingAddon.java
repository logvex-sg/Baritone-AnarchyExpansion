package dev.basehunt.bhclient;

import com.mojang.logging.LogUtils;
import dev.basehunt.bhclient.commands.BaseHuntCommand;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.hud.BaseHuntingHud;
import dev.basehunt.bhclient.modules.AutoLog;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.BaseHunterV2;
import dev.basehunt.bhclient.modules.AutoStorage;
import dev.basehunt.bhclient.modules.AutomationBrain;
import dev.basehunt.bhclient.modules.BaritoneControl;
import dev.basehunt.bhclient.modules.ChatCommands;
import dev.basehunt.bhclient.modules.CommandTerminal;
import dev.basehunt.bhclient.modules.DeathRecovery;
import dev.basehunt.bhclient.modules.PlayerRadar;
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
import dev.basehunt.bhclient.modules.StashFinder;
import dev.basehunt.bhclient.modules.StorageMonitor;
import dev.basehunt.bhclient.modules.StorageNetwork;
import dev.basehunt.bhclient.modules.SurvivalManager;
import dev.basehunt.bhclient.modules.WebhookNotifier;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.DeathStore;
import dev.basehunt.bhclient.systems.EnderChestStore;
import dev.basehunt.bhclient.systems.ExplorationStore;
import dev.basehunt.bhclient.systems.KitBotStore;
import dev.basehunt.bhclient.systems.KitLoadoutStore;
import dev.basehunt.bhclient.systems.NavigationManager;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.SixbtPlayerStore;
import dev.basehunt.bhclient.systems.SixbtServerStore;
import dev.basehunt.bhclient.systems.StorageAutomation;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.systems.WorkflowManager;
import dev.basehunt.bhclient.utils.WebhookManager;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class BaseHuntingAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Baritone Anarchy Expansion");
    public static final HudGroup HUD_GROUP = new HudGroup("Baritone Anarchy Expansion");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Baritone Anarchy Expansion");

        // Systems have to be registered before Modules.load() runs, otherwise their saved data is lost.
        Systems.add(new StashManager());
        Systems.add(new PlayerTracker());
        Systems.add(new EnderChestStore());
        Systems.add(new ExplorationStore());
        Systems.add(new StorageRegistry());
        Systems.add(new WorkflowManager());
        Systems.add(new KitBotStore());
        Systems.add(new KitLoadoutStore());
        Systems.add(new SixbtServerStore());
        Systems.add(new SixbtPlayerStore());
        Systems.add(new DeathStore());

        Modules.get().add(new BaseHunter());
        Modules.get().add(new BaseHunterV2());
        Modules.get().add(new NewChunks());
        Modules.get().add(new StashFinder());
        Modules.get().add(new WebhookNotifier());
        Modules.get().add(new PlayerLogger());
        Modules.get().add(new AutoLog());
        Modules.get().add(new MendingManager());
        Modules.get().add(new EnderChestManager());
        Modules.get().add(new SurvivalManager());
        Modules.get().add(new InventoryManager());
        Modules.get().add(new ChestSorter());
        Modules.get().add(new StorageNetwork());
        Modules.get().add(new ShulkerManager());
        Modules.get().add(new RestockManager());
        Modules.get().add(new StorageMonitor());
        Modules.get().add(new KitBot());
        Modules.get().add(new SixbtHomeManager());
        Modules.get().add(new SixbtTpaManager());
        Modules.get().add(new SixbtTpsMonitor());
        Modules.get().add(new SixbtHotspotTracker());
        Modules.get().add(new SixbtPlayerTracker());
        Modules.get().add(new ChatCommands());
        Modules.get().add(new CommandTerminal());
        Modules.get().add(new AutoStorage());
        Modules.get().add(new AutomationBrain());
        Modules.get().add(new BaritoneControl());
        Modules.get().add(new PlayerRadar());
        Modules.get().add(new DeathRecovery());

        Commands.add(new BaseHuntCommand());

        Hud.get().register(BaseHuntingHud.INFO);

        WebhookManager.get().start();
        AutomationManager.install();
        ContainerInteractionManager.install();

        // The navigation layer and the automation engine are installed last so their event subscriptions are in
        // place before any module can submit a task.
        NavigationManager.install();
        AutomationEngine engine = AutomationEngine.install();
        engine.registerFactory(dev.basehunt.bhclient.core.task.Task.Category.WITHDRAW,
            () -> new StorageAutomation(StorageAutomation.Direction.WITHDRAW));
        engine.registerFactory(dev.basehunt.bhclient.core.task.Task.Category.DEPOSIT,
            () -> new StorageAutomation(StorageAutomation.Direction.DEPOSIT));
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.basehunt.bhclient";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("logvex-sg", "Baritone-AnarchyExpansion", "main", null);
    }
}
