package com.yunexal.physex;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Dual-platform scheduler bridge supporting both traditional Paper/Purpur
 * and regionized multithreaded Folia servers cleanly.
 */
public final class SchedulerBridge {

    private static final boolean FOLIA = detectFolia();

    private SchedulerBridge() {
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public static boolean canAccessBlockNow(World world, int blockX, int blockZ) {
        if (!FOLIA) {
            return Bukkit.isPrimaryThread();
        }
        return Bukkit.isOwnedByCurrentRegion(world, blockX >> 4, blockZ >> 4);
    }

    public static void runTask(Plugin plugin, Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public static void runAtRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    public static void runAtBlockLater(Plugin plugin, Block block, Runnable task, long delayTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(
                plugin,
                block.getWorld(),
                block.getX() >> 4,
                block.getZ() >> 4,
                scheduled -> task.run(),
                safeDelay
        );
    }

    public static void runAtRegionDelayed(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, scheduled -> task.run(), safeDelay);
    }

    public static void runAtLocation(Plugin plugin, Location loc, Runnable task) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        runAtRegion(plugin, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4, task);
    }

    public static void runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        if (entity == null) {
            return;
        }
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        entity.getScheduler().run(plugin, scheduled -> task.run(), null);
    }

    public static CancellableTask runGlobalAtFixedRate(Plugin plugin, Runnable task, long initialDelay, long period) {
        long safeInit = Math.max(1L, initialDelay);
        long safePeriod = Math.max(1L, period);
        if (!FOLIA) {
            BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, safeInit, safePeriod);
            return bt::cancel;
        }
        var ft = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(), safeInit, safePeriod);
        return ft::cancel;
    }

    public interface CancellableTask {
        void cancel();
    }
}
