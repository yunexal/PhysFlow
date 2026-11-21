package com.yunexal.physex;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Isolated Folia scheduler calls so 1.16.5-1.19.3 Paper/Spigot servers
 * never link Folia-only Bukkit methods.
 */
final class FoliaSchedulerHelper {

    private FoliaSchedulerHelper() {
    }

    static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    static void runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    static void runRegionDelayed(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, scheduled -> task.run(), delayTicks);
    }

    static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, scheduled -> task.run(), null);
    }

    static SchedulerBridge.CancellableTask runGlobalAtFixedRate(Plugin plugin, Runnable task, long initialDelay, long period) {
        var ft = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(), initialDelay, period);
        return ft::cancel;
    }
}
