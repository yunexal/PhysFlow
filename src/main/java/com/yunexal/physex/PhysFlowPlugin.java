package com.yunexal.physex;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main entrypoint for PhysFlow standalone finite fluid physics plugin.
 */
public final class PhysFlowPlugin extends JavaPlugin {

    private PhysExConfig physExConfig;
    private MessageManager messageManager;
    private FluidSimulationEngine engine;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.physExConfig = new PhysExConfig();
        this.physExConfig.load(getConfig());

        this.messageManager = new MessageManager(this, physExConfig);
        this.messageManager.reload();

        this.engine = new FluidSimulationEngine(this, physExConfig);
        Bukkit.getPluginManager().registerEvents(new FluidListeners(this, physExConfig, engine), this);

        PhysExCommand commandHandler = new PhysExCommand(this, physExConfig, messageManager, engine);
        PluginCommand cmd = getCommand("physflow");
        if (cmd != null) {
            cmd.setExecutor(commandHandler);
            cmd.setTabCompleter(commandHandler);
        }

        engine.start();
        String runtime = SchedulerBridge.isFolia() ? "Folia (Regionized)" : "Paper/Purpur";
        String pipeline = physExConfig.asyncProcessing
                ? ("ASYNC/" + physExConfig.asyncWorkerThreads + "w")
                : "SYNC";
        getLogger().info("[PhysFlow] Enabled full finite Water & Lava physics on " + runtime
                + " [" + pipeline + "] (maxTickMs=" + physExConfig.maxTickTimeMs
                + ", maxUpdatesPerTick=" + physExConfig.maxUpdatesPerTick + ").");
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        physExConfig.load(getConfig());
        if (engine != null) {
            engine.ensureAsyncPoolIfNeeded();
        }
        if (messageManager != null) {
            messageManager.reload();
        }
    }
}
