package com.yunexal.physex;

import com.yunexal.physex.AsyncFluidBatch.BatchItem;
import com.yunexal.physex.AsyncFluidBatch.SolvedPlan;
import com.yunexal.physex.AsyncFluidBatch.WorldSnapshotContext;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core finite fluid simulation engine for PhysEx.
 * Dual-mode architecture (Paper/Purpur + Folia) with background async solving:
 * - Stage 1 (Tick/Region thread): fast ChunkSnapshot capture for active queued chunks.
 * - Stage 2 (Background worker pool): full finite fluid math, pressure BFS, collisions & diff planning.
 * - Stage 3 (Tick/Region thread): optimistic verification + atomic BlockData apply.
 */
public class FluidSimulationEngine {

    public enum FluidKind {
        WATER(Material.WATER),
        LAVA(Material.LAVA);

        public final Material material;

        FluidKind(Material material) {
            this.material = material;
        }

        public FluidKind opposite() {
            return this == WATER ? LAVA : WATER;
        }
    }

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int[] DX6 = {1, -1, 0, 0, 0, 0};
    private static final int[] DY6 = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ6 = {0, 0, 0, 0, 1, -1};
    private static final int MAX_SNAPSHOTS_PER_BATCH = 48;

    private final JavaPlugin plugin;
    private final PhysExConfig config;

    // Paper/Purpur queues and per-world async flight guards
    private final Map<UUID, LongPosSetQueue> paperWaterQueues = new HashMap<>();
    private final Map<UUID, LongPosSetQueue> paperLavaQueues = new HashMap<>();
    private final ConcurrentHashMap<UUID, AtomicBoolean> paperWorldAsyncBusy = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<BatchItem>> paperInFlightItems = new ConcurrentHashMap<>();

    // Folia regionized queues keyed by (worldId, chunkX, chunkZ)
    private final ConcurrentHashMap<RegionKey, RegionBucket> foliaBuckets = new ConcurrentHashMap<>();

    private final ThreadLocal<Boolean> internalUpdate = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile ExecutorService asyncExecutor;
    private int configuredWorkerCount = 0;

    private SchedulerBridge.CancellableTask mainTask;
    private SchedulerBridge.CancellableTask rainTask;

    private long tickCounter = 0L;
    private volatile boolean paused = false;

    private final AtomicLong totalUpdatesProcessed = new AtomicLong(0L);
    private final AtomicLong totalThrottledTicks = new AtomicLong(0L);
    private volatile double lastTickElapsedMs = 0.0;
    private volatile double lastAsyncSolveMs = 0.0;
    private volatile int lastTickUpdatedBlocks = 0;

    public FluidSimulationEngine(JavaPlugin plugin, PhysExConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public PhysExConfig getConfig() {
        return config;
    }

    public void start() {
        shuttingDown.set(false);
        ensureAsyncPoolIfNeeded();
        loadQueueState();
        if (!SchedulerBridge.isFolia()) {
            mainTask = SchedulerBridge.runGlobalAtFixedRate(plugin, this::tickPaper, 1L, 1L);
        } else {
            rainTask = SchedulerBridge.runGlobalAtFixedRate(plugin, this::tickRainFoliaCoordinator, 20L, 20L);
            for (Map.Entry<RegionKey, RegionBucket> entry : foliaBuckets.entrySet()) {
                RegionKey rk = entry.getKey();
                World world = Bukkit.getWorld(rk.worldId());
                if (world != null) {
                    scheduleFoliaBucketIfNeeded(world, rk, entry.getValue(), FluidKind.WATER);
                    scheduleFoliaBucketIfNeeded(world, rk, entry.getValue(), FluidKind.LAVA);
                }
            }
        }
    }

    public synchronized void ensureAsyncPoolIfNeeded() {
        if (!config.asyncProcessing) {
            return;
        }
        int desired = Math.max(1, config.asyncWorkerThreads);
        if (asyncExecutor != null && !asyncExecutor.isShutdown() && configuredWorkerCount == desired) {
            return;
        }
        shutdownExecutorGracefully();
        AtomicInteger threadNum = new AtomicInteger(1);
        configuredWorkerCount = desired;
        asyncExecutor = Executors.newFixedThreadPool(desired, r -> {
            Thread t = new Thread(r, "PhysEx-Worker-" + threadNum.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    private void shutdownExecutorGracefully() {
        ExecutorService exec = asyncExecutor;
        asyncExecutor = null;
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(400L, TimeUnit.MILLISECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException ex) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void shutdown() {
        shuttingDown.set(true);
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
        if (rainTask != null) {
            rainTask.cancel();
            rainTask = null;
        }
        shutdownExecutorGracefully();
        if (config.persistQueueOnShutdown) {
            saveQueueState();
        }
        paperWaterQueues.clear();
        paperLavaQueues.clear();
        paperWorldAsyncBusy.clear();
        paperInFlightItems.clear();
        foliaBuckets.clear();
    }

    public boolean isInternalUpdate() {
        return Boolean.TRUE.equals(internalUpdate.get());
    }

    public void runInternalUpdate(Runnable action) {
        internalUpdate.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            internalUpdate.set(Boolean.FALSE);
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public long getTotalUpdatesProcessed() {
        return totalUpdatesProcessed.get();
    }

    public long getTotalThrottledTicks() {
        return totalThrottledTicks.get();
    }

    public double getLastTickElapsedMs() {
        return lastTickElapsedMs;
    }

    public double getLastAsyncSolveMs() {
        return lastAsyncSolveMs;
    }

    public int getLastTickUpdatedBlocks() {
        return lastTickUpdatedBlocks;
    }

    public double getCurrentTpsSafe() {
        if (SchedulerBridge.isFolia()) {
            return 20.0;
        }
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0 && !Double.isNaN(tps[0])) {
                return Math.min(20.0, tps[0]);
            }
        } catch (UnsupportedOperationException | NoSuchMethodError ignored) {
        }
        return 20.0;
    }

    public int getPendingWaterCount() {
        if (!SchedulerBridge.isFolia()) {
            int sum = 0;
            for (LongPosSetQueue q : paperWaterQueues.values()) {
                sum += q.size();
            }
            return sum;
        }
        int sum = 0;
        for (RegionBucket bucket : foliaBuckets.values()) {
            synchronized (bucket) {
                sum += bucket.waterQueue.size();
            }
        }
        return sum;
    }

    public int getPendingLavaCount() {
        if (!SchedulerBridge.isFolia()) {
            int sum = 0;
            for (LongPosSetQueue q : paperLavaQueues.values()) {
                sum += q.size();
            }
            return sum;
        }
        int sum = 0;
        for (RegionBucket bucket : foliaBuckets.values()) {
            synchronized (bucket) {
                sum += bucket.lavaQueue.size();
            }
        }
        return sum;
    }

    public void clearAllQueues() {
        paperWaterQueues.clear();
        paperLavaQueues.clear();
        foliaBuckets.clear();
    }

    public void enqueue(World world, int x, int y, int z, FluidKind kind) {
        if (world == null || !config.isWorldEnabled(world)) {
            return;
        }
        if (kind == FluidKind.WATER && (!config.waterEnabled || !config.waterFiniteMode)) {
            return;
        }
        if (kind == FluidKind.LAVA && (!config.lavaEnabled || !config.lavaFiniteMode)) {
            return;
        }
        if (y < VersionCompat.getMinHeight(world) || y >= world.getMaxHeight()) {
            return;
        }

        if (!SchedulerBridge.isFolia()) {
            Map<UUID, LongPosSetQueue> map = kind == FluidKind.WATER ? paperWaterQueues : paperLavaQueues;
            LongPosSetQueue q = map.computeIfAbsent(world.getUID(), id -> new LongPosSetQueue(256));
            q.enqueue(x, y, z, config.maxQueueSize);
            return;
        }

        int cx = x >> 4;
        int cz = z >> 4;
        RegionKey rk = new RegionKey(world.getUID(), cx, cz);
        RegionBucket bucket = foliaBuckets.computeIfAbsent(rk, k -> new RegionBucket());
        synchronized (bucket) {
            int perRegionMax = Math.max(1024, config.maxQueueSize / 8);
            bucket.queue(kind).enqueue(x, y, z, perRegionMax);
        }
        scheduleFoliaBucketIfNeeded(world, rk, bucket, kind);
    }

    private void scheduleFoliaBucketIfNeeded(World world, RegionKey rk, RegionBucket bucket, FluidKind kind) {
        boolean empty;
        synchronized (bucket) {
            empty = bucket.queue(kind).isEmpty();
        }
        if (empty) {
            return;
        }
        AtomicBoolean flag = bucket.scheduledFlag(kind);
        if (!flag.compareAndSet(false, true)) {
            return;
        }
        long delay = resolveInterval(world, kind);
        SchedulerBridge.runAtRegionDelayed(plugin, world, rk.chunkX(), rk.chunkZ(),
                () -> processFoliaBucket(world, rk, bucket, kind), delay);
    }

    private long resolveInterval(World world, FluidKind kind) {
        if (kind == FluidKind.WATER) {
            return Math.max(1L, config.waterTickInterval);
        }
        return world.getEnvironment() == World.Environment.NETHER
                ? Math.max(1L, config.lavaNetherTickInterval)
                : Math.max(1L, config.lavaTickInterval);
    }

    private void processFoliaBucket(World world, RegionKey rk, RegionBucket bucket, FluidKind kind) {
        boolean handedOffToAsync = false;
        try {
            if (shuttingDown.get() || !config.isWorldEnabled(world) || !world.isChunkLoaded(rk.chunkX(), rk.chunkZ())) {
                synchronized (bucket) {
                    bucket.queue(kind).clear();
                }
                return;
            }
            if (paused) {
                return;
            }

            if (config.asyncProcessing && asyncExecutor != null && !asyncExecutor.isShutdown()) {
                AtomicBoolean busy = bucket.asyncFlag(kind);
                if (busy.compareAndSet(false, true)) {
                    handedOffToAsync = dispatchFoliaAsyncBatch(world, rk, bucket, kind, busy);
                    if (handedOffToAsync) {
                        return;
                    }
                    busy.set(false);
                } else {
                    return;
                }
            }

            long startNs = System.nanoTime();
            long deadlineNs = startNs + (long) (config.maxTickTimeMs * 1_000_000.0);
            int maxOps = Math.max(16, config.maxUpdatesPerTick);

            long[] batch;
            synchronized (bucket) {
                LongPosSetQueue q = bucket.queue(kind);
                int take = Math.min(q.size(), maxOps);
                batch = new long[take];
                for (int i = 0; i < take; i++) {
                    batch[i] = q.pollPacked();
                }
            }

            LiveFluidGrid liveGrid = new LiveFluidGrid(world);
            int updated = 0;
            int processedIdx = 0;
            for (; processedIdx < batch.length; processedIdx++) {
                if (System.nanoTime() >= deadlineNs && processedIdx > 0) {
                    break;
                }
                long packed = batch[processedIdx];
                int x = LongPosSetQueue.unpackX(packed);
                int y = LongPosSetQueue.unpackY(packed);
                int z = LongPosSetQueue.unpackZ(packed);
                if (!SchedulerBridge.canAccessBlockNow(world, x, z)) {
                    enqueue(world, x, y, z, kind);
                    continue;
                }
                if (simulateStep(liveGrid, x, y, z, kind)) {
                    updated++;
                }
            }

            if (processedIdx < batch.length) {
                synchronized (bucket) {
                    LongPosSetQueue q = bucket.queue(kind);
                    int perRegionMax = Math.max(1024, config.maxQueueSize / 8);
                    for (int i = processedIdx; i < batch.length && q.size() < perRegionMax; i++) {
                        q.enqueuePacked(batch[i]);
                    }
                }
                totalThrottledTicks.incrementAndGet();
            }

            if (updated > 0) {
                totalUpdatesProcessed.addAndGet(updated);
                lastTickUpdatedBlocks = updated;
            }
            lastTickElapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        } finally {
            if (!handedOffToAsync) {
                finishFoliaBucketCycle(world, rk, bucket, kind);
            }
        }
    }

    private boolean dispatchFoliaAsyncBatch(World world, RegionKey rk, RegionBucket bucket,
                                            FluidKind kind, AtomicBoolean busy) {
        long startNs = System.nanoTime();
        int maxOps = Math.max(16, config.maxUpdatesPerTick);
        List<BatchItem> items = new ArrayList<>();
        synchronized (bucket) {
            LongPosSetQueue q = bucket.queue(kind);
            int take = Math.min(q.size(), maxOps);
            for (int i = 0; i < take; i++) {
                items.add(new BatchItem(q.pollPacked(), kind));
            }
            bucket.setInFlight(kind, items);
        }
        if (items.isEmpty()) {
            bucket.clearInFlight(kind);
            return false;
        }

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        Set<Long> unowned = new HashSet<>();
        boolean includeTemp = config.evaporateThinPuddlesInDryBiomes;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int ncx = rk.chunkX() + dx;
                int ncz = rk.chunkZ() + dz;
                long ck = AsyncFluidBatch.chunkKey(ncx, ncz);
                if (!world.isChunkLoaded(ncx, ncz)) {
                    continue;
                }
                if (SchedulerBridge.isOwnedByCurrentRegion(world, ncx, ncz)) {
                    snapshots.put(ck, world.getChunkAt(ncx, ncz).getChunkSnapshot(false, false, includeTemp, false));
                } else {
                    unowned.add(ck);
                }
            }
        }

        WorldSnapshotContext ctx = new WorldSnapshotContext(
                world.getEnvironment() == World.Environment.NETHER,
                VersionCompat.getMinHeight(world),
                world.getMaxHeight(),
                snapshots,
                unowned
        );
        double captureMs = (System.nanoTime() - startNs) / 1_000_000.0;

        asyncExecutor.execute(() -> {
            SolvedPlan plan;
            try {
                if (shuttingDown.get() || paused) {
                    plan = null;
                } else {
                    plan = AsyncFluidBatch.solveInBackground(ctx, items, config, this);
                    lastAsyncSolveMs = plan.solveDurationMs;
                }
            } catch (Throwable ex) {
                plugin.getLogger().warning("Async fluid solve error: " + ex.getMessage());
                plan = null;
            }

            SolvedPlan finalPlan = plan;
            SchedulerBridge.runAtRegion(plugin, world, rk.chunkX(), rk.chunkZ(), () -> {
                long applyStartNs = System.nanoTime();
                try {
                    if (!shuttingDown.get() && !paused && finalPlan != null
                            && config.isWorldEnabled(world) && world.isChunkLoaded(rk.chunkX(), rk.chunkZ())) {
                        int applied = AsyncFluidBatch.applyPlan(world, finalPlan, this, plugin);
                        if (applied > 0) {
                            totalUpdatesProcessed.addAndGet(applied);
                            lastTickUpdatedBlocks = applied;
                        }
                    } else if (!shuttingDown.get() && finalPlan == null) {
                        for (BatchItem bi : items) {
                            enqueue(world,
                                    LongPosSetQueue.unpackX(bi.packedPos()),
                                    LongPosSetQueue.unpackY(bi.packedPos()),
                                    LongPosSetQueue.unpackZ(bi.packedPos()),
                                    bi.kind());
                        }
                    }
                    double applyMs = (System.nanoTime() - applyStartNs) / 1_000_000.0;
                    lastTickElapsedMs = captureMs + applyMs;
                } finally {
                    bucket.clearInFlight(kind);
                    busy.set(false);
                    finishFoliaBucketCycle(world, rk, bucket, kind);
                }
            });
        });
        return true;
    }

    private void finishFoliaBucketCycle(World world, RegionKey rk, RegionBucket bucket, FluidKind kind) {
        bucket.scheduledFlag(kind).set(false);
        boolean hasMore;
        boolean bothEmpty;
        synchronized (bucket) {
            hasMore = !bucket.queue(kind).isEmpty();
            bothEmpty = bucket.waterQueue.isEmpty() && bucket.lavaQueue.isEmpty()
                    && !bucket.waterScheduled.get() && !bucket.lavaScheduled.get()
                    && !bucket.waterAsyncBusy.get() && !bucket.lavaAsyncBusy.get();
        }
        if (hasMore && !shuttingDown.get() && config.isWorldEnabled(world) && world.isChunkLoaded(rk.chunkX(), rk.chunkZ())) {
            scheduleFoliaBucketIfNeeded(world, rk, bucket, kind);
        } else if (bothEmpty) {
            foliaBuckets.remove(rk, bucket);
        }
    }

    public void enqueueBlockAndNeighbors(Block center) {
        if (center == null || !config.isWorldEnabled(center.getWorld())) {
            return;
        }
        World w = center.getWorld();
        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();
        enqueueBothIfFluidOrOpen(w, x, y, z);
        for (int i = 0; i < 6; i++) {
            enqueueBothIfFluidOrOpen(w, x + DX6[i], y + DY6[i], z + DZ6[i]);
        }
    }

    public void enqueueNeighborsOnly(Block center, FluidKind kind) {
        World w = center.getWorld();
        enqueueNeighborsOnly(w, center.getX(), center.getY(), center.getZ(), kind);
    }

    private void enqueueNeighborsOnly(World w, int x, int y, int z, FluidKind kind) {
        enqueue(w, x, y, z, kind);
        for (int i = 0; i < 6; i++) {
            enqueue(w, x + DX6[i], y + DY6[i], z + DZ6[i], kind);
        }
    }

    private void enqueueBothIfFluidOrOpen(World w, int x, int y, int z) {
        if (y < VersionCompat.getMinHeight(w) || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        if (SchedulerBridge.isFolia() && !SchedulerBridge.canAccessBlockNow(w, x, z)) {
            SchedulerBridge.runAtRegion(plugin, w, x >> 4, z >> 4, () -> enqueueBothIfFluidOrOpen(w, x, y, z));
            return;
        }
        Block b = w.getBlockAt(x, y, z);
        Material t = b.getType();
        if (t == Material.WATER || isMarineFlora(t) || isWaterloggedState(b)) {
            enqueue(w, x, y, z, FluidKind.WATER);
        } else if (t == Material.LAVA) {
            enqueue(w, x, y, z, FluidKind.LAVA);
        } else {
            for (int i = 0; i < 6; i++) {
                int nx = x + DX6[i];
                int ny = y + DY6[i];
                int nz = z + DZ6[i];
                if (ny < VersionCompat.getMinHeight(w) || ny >= w.getMaxHeight() || !w.isChunkLoaded(nx >> 4, nz >> 4)) {
                    continue;
                }
                if (SchedulerBridge.isFolia() && !SchedulerBridge.canAccessBlockNow(w, nx, nz)) {
                    SchedulerBridge.runAtRegion(plugin, w, nx >> 4, nz >> 4, () -> wakeCellIfFluid(w, nx, ny, nz));
                    continue;
                }
                wakeCellIfFluid(w, nx, ny, nz);
            }
        }
    }

    private void wakeCellIfFluid(World w, int x, int y, int z) {
        if (y < VersionCompat.getMinHeight(w) || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        Block nb = w.getBlockAt(x, y, z);
        Material nt = nb.getType();
        if (nt == Material.WATER || isMarineFlora(nt) || isWaterloggedState(nb)) {
            enqueue(w, x, y, z, FluidKind.WATER);
        } else if (nt == Material.LAVA) {
            enqueue(w, x, y, z, FluidKind.LAVA);
        }
    }

    // =========================================================================
    // PAPER/PURPUR TICK LOOP (ASYNC BACKGROUND SOLVE OR SYNC FALLBACK)
    // =========================================================================

    private void tickPaper() {
        tickCounter++;
        if (shuttingDown.get() || !config.generalEnabled || paused) {
            return;
        }

        double curTps = getCurrentTpsSafe();
        if (curTps < config.minTpsThreshold) {
            totalThrottledTicks.incrementAndGet();
            return;
        }

        if (config.rainFillsWorld && config.waterEnabled && config.waterFiniteMode && tickCounter % 20L == 0L) {
            tickRainPuddlesPaper();
        }

        if (config.asyncProcessing) {
            ensureAsyncPoolIfNeeded();
            if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
                tickPaperAsync();
                return;
            }
        }

        tickPaperSync();
    }

    private void tickPaperAsync() {
        boolean waterDue = config.waterEnabled && config.waterFiniteMode && (tickCounter % config.waterTickInterval == 0L);
        boolean lavaAllowed = config.lavaEnabled && config.lavaFiniteMode;
        if (!waterDue && !lavaAllowed) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            if (!config.isWorldEnabled(world)) {
                continue;
            }
            UUID wid = world.getUID();
            LongPosSetQueue wq = waterDue ? paperWaterQueues.get(wid) : null;
            boolean hasWater = wq != null && !wq.isEmpty();

            int lavaInterval = world.getEnvironment() == World.Environment.NETHER
                    ? config.lavaNetherTickInterval
                    : config.lavaTickInterval;
            LongPosSetQueue lq = (lavaAllowed && tickCounter % lavaInterval == 0L) ? paperLavaQueues.get(wid) : null;
            boolean hasLava = lq != null && !lq.isEmpty();

            if (!hasWater && !hasLava) {
                continue;
            }

            AtomicBoolean busy = paperWorldAsyncBusy.computeIfAbsent(wid, id -> new AtomicBoolean(false));
            if (!busy.compareAndSet(false, true)) {
                continue;
            }

            long startNs = System.nanoTime();
            long snapDeadlineNs = startNs + (long) (config.maxTickTimeMs * 0.65 * 1_000_000.0);
            int budget = config.maxUpdatesPerTick;
            Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
            List<BatchItem> batch = new ArrayList<>();
            boolean includeTemp = config.evaporateThinPuddlesInDryBiomes;
            boolean clipped = false;

            if (hasWater) {
                clipped = collectPaperSnapshotBatch(world, wq, FluidKind.WATER, budget, snapDeadlineNs, includeTemp, snapshots, batch);
            }
            int remaining = budget - batch.size();
            if (hasLava && remaining > 0 && !clipped) {
                clipped = collectPaperSnapshotBatch(world, lq, FluidKind.LAVA, remaining, snapDeadlineNs, includeTemp, snapshots, batch);
            }

            if (clipped) {
                totalThrottledTicks.incrementAndGet();
            }
            if (batch.isEmpty()) {
                busy.set(false);
                continue;
            }

            paperInFlightItems.put(wid, batch);
            WorldSnapshotContext ctx = new WorldSnapshotContext(
                    world.getEnvironment() == World.Environment.NETHER,
                    VersionCompat.getMinHeight(world),
                    world.getMaxHeight(),
                    snapshots,
                    Set.of()
            );
            double captureMs = (System.nanoTime() - startNs) / 1_000_000.0;

            asyncExecutor.execute(() -> {
                SolvedPlan plan;
                try {
                    if (shuttingDown.get() || paused) {
                        plan = null;
                    } else {
                        plan = AsyncFluidBatch.solveInBackground(ctx, batch, config, this);
                        lastAsyncSolveMs = plan.solveDurationMs;
                    }
                } catch (Throwable ex) {
                    plugin.getLogger().warning("Async fluid solve error: " + ex.getMessage());
                    plan = null;
                }

                SolvedPlan finalPlan = plan;
                SchedulerBridge.runTask(plugin, () -> {
                    long applyStartNs = System.nanoTime();
                    try {
                        if (!shuttingDown.get() && !paused && finalPlan != null && config.isWorldEnabled(world)) {
                            int applied = AsyncFluidBatch.applyPlan(world, finalPlan, this, plugin);
                            lastTickUpdatedBlocks = applied;
                            if (applied > 0) {
                                totalUpdatesProcessed.addAndGet(applied);
                            }
                        } else if (!shuttingDown.get() && finalPlan == null) {
                            for (BatchItem bi : batch) {
                                enqueue(world,
                                        LongPosSetQueue.unpackX(bi.packedPos()),
                                        LongPosSetQueue.unpackY(bi.packedPos()),
                                        LongPosSetQueue.unpackZ(bi.packedPos()),
                                        bi.kind());
                            }
                        }
                        double applyMs = (System.nanoTime() - applyStartNs) / 1_000_000.0;
                        lastTickElapsedMs = captureMs + applyMs;
                    } finally {
                        paperInFlightItems.remove(wid);
                        busy.set(false);
                    }
                });
            });
        }
    }

    private boolean collectPaperSnapshotBatch(World world, LongPosSetQueue q, FluidKind kind, int maxTake,
                                              long snapDeadlineNs, boolean includeTemp,
                                              Map<Long, ChunkSnapshot> snapshots, List<BatchItem> outBatch) {
        int wave = Math.min(q.size(), maxTake);
        boolean clipped = false;
        while (wave > 0 && !q.isEmpty()) {
            long packed = q.pollPacked();
            wave--;
            int x = LongPosSetQueue.unpackX(packed);
            int y = LongPosSetQueue.unpackY(packed);
            int z = LongPosSetQueue.unpackZ(packed);
            if (y < VersionCompat.getMinHeight(world) || y >= world.getMaxHeight()) {
                continue;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }

            long ck = AsyncFluidBatch.chunkKey(cx, cz);
            if (!snapshots.containsKey(ck)) {
                if (snapshots.size() >= MAX_SNAPSHOTS_PER_BATCH || (System.nanoTime() >= snapDeadlineNs && !outBatch.isEmpty())) {
                    q.enqueuePacked(packed);
                    clipped = true;
                    break;
                }
                snapshots.put(ck, world.getChunkAt(cx, cz).getChunkSnapshot(false, false, includeTemp, false));
                captureNeighborSnapshots(world, cx, cz, includeTemp, snapshots);
            }
            outBatch.add(new BatchItem(packed, kind));
        }
        return clipped;
    }

    private void captureNeighborSnapshots(World world, int cx, int cz, boolean includeTemp,
                                          Map<Long, ChunkSnapshot> snapshots) {
        for (int i = 0; i < 4; i++) {
            int ncx = cx + DX[i];
            int ncz = cz + DZ[i];
            long nck = AsyncFluidBatch.chunkKey(ncx, ncz);
            if (snapshots.containsKey(nck) || snapshots.size() >= MAX_SNAPSHOTS_PER_BATCH) {
                continue;
            }
            if (world.isChunkLoaded(ncx, ncz)) {
                snapshots.put(nck, world.getChunkAt(ncx, ncz).getChunkSnapshot(false, false, includeTemp, false));
            }
        }
    }

    private void tickPaperSync() {
        lastTickElapsedMs = 0.0;
        lastTickUpdatedBlocks = 0;
        lastAsyncSolveMs = 0.0;

        long startNs = System.nanoTime();
        long deadlineNs = startNs + (long) (config.maxTickTimeMs * 1_000_000.0);
        int remainingUpdates = config.maxUpdatesPerTick;

        boolean runWater = config.waterEnabled && config.waterFiniteMode && (tickCounter % config.waterTickInterval == 0L);
        if (runWater && remainingUpdates > 0) {
            for (World world : Bukkit.getWorlds()) {
                if (!config.isWorldEnabled(world)) {
                    continue;
                }
                LongPosSetQueue q = paperWaterQueues.get(world.getUID());
                if (q != null && !q.isEmpty()) {
                    remainingUpdates = processPaperSingleWorldQueueSync(world, q, FluidKind.WATER, deadlineNs, remainingUpdates);
                    if (remainingUpdates <= 0 || System.nanoTime() >= deadlineNs) {
                        break;
                    }
                }
            }
        }

        if (config.lavaEnabled && config.lavaFiniteMode && remainingUpdates > 0 && System.nanoTime() < deadlineNs) {
            for (World world : Bukkit.getWorlds()) {
                if (!config.isWorldEnabled(world)) {
                    continue;
                }
                int interval = world.getEnvironment() == World.Environment.NETHER
                        ? config.lavaNetherTickInterval
                        : config.lavaTickInterval;
                if (tickCounter % interval == 0L) {
                    LongPosSetQueue q = paperLavaQueues.get(world.getUID());
                    if (q != null && !q.isEmpty()) {
                        remainingUpdates = processPaperSingleWorldQueueSync(world, q, FluidKind.LAVA, deadlineNs, remainingUpdates);
                        if (remainingUpdates <= 0 || System.nanoTime() >= deadlineNs) {
                            break;
                        }
                    }
                }
            }
        }

        lastTickElapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        if (System.nanoTime() >= deadlineNs && (getPendingWaterCount() > 0 || getPendingLavaCount() > 0)) {
            totalThrottledTicks.incrementAndGet();
        }
    }

    private int processPaperSingleWorldQueueSync(World world, LongPosSetQueue q, FluidKind kind, long deadlineNs, int budget) {
        LiveFluidGrid liveGrid = new LiveFluidGrid(world);
        int remaining = budget;
        int waveMax = Math.min(q.size(), remaining);
        while (waveMax > 0 && remaining > 0 && !q.isEmpty() && System.nanoTime() < deadlineNs) {
            long packed = q.pollPacked();
            waveMax--;
            int x = LongPosSetQueue.unpackX(packed);
            int y = LongPosSetQueue.unpackY(packed);
            int z = LongPosSetQueue.unpackZ(packed);
            if (y < VersionCompat.getMinHeight(world) || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (simulateStep(liveGrid, x, y, z, kind)) {
                lastTickUpdatedBlocks++;
                totalUpdatesProcessed.incrementAndGet();
            }
            remaining--;
        }
        return remaining;
    }

    // =========================================================================
    // SHARED SIMULATION CORE (USED BY BOTH ASYNC WORKER & SYNC FALLBACK)
    // =========================================================================

    boolean simulateStep(FluidGrid grid, int x, int y, int z, FluidKind kind) {
        if (!grid.canAccess(x, y, z)) {
            return false;
        }

        if (kind == FluidKind.WATER && config.unfreezeMarineFloraOnDrain && FluidGrid.isMarineFlora(grid.getMaterial(x, y, z))) {
            if (shouldUnfreezeMarineFlora(grid, x, y, z)) {
                grid.convertMarineFloraToWater(x, y, z, config);
                grid.enqueueNeighborsOnly(x, y, z, FluidKind.WATER);
            } else {
                return false;
            }
        }

        int vol = grid.normalizeAndReadVolume(x, y, z, kind, config);
        if (vol <= 0) {
            return false;
        }

        if (kind == FluidKind.WATER && config.unfreezeMarineFloraOnDrain) {
            wakeAdjacentMarineFlora(grid, x, y, z);
        }

        // Phase 1: Water <-> Lava reactions
        if (config.lavaWaterMode != PhysExConfig.LavaWaterMode.VANILLA) {
            FluidKind opp = kind.opposite();
            for (int i = 0; i < 6; i++) {
                int ny = y + DY6[i];
                if (ny < grid.minY() || ny >= grid.maxY()) {
                    continue;
                }
                int nx = x + DX6[i];
                int nz = z + DZ6[i];
                if (!grid.canAccess(nx, ny, nz)) {
                    continue;
                }
                int oppVol = grid.getFluidLevelRaw(nx, ny, nz, opp);
                if (oppVol > 0) {
                    int wX = kind == FluidKind.WATER ? x : nx;
                    int wY = kind == FluidKind.WATER ? y : ny;
                    int wZ = kind == FluidKind.WATER ? z : nz;
                    int wVol = kind == FluidKind.WATER ? vol : oppVol;

                    int lX = kind == FluidKind.LAVA ? x : nx;
                    int lY = kind == FluidKind.LAVA ? y : ny;
                    int lZ = kind == FluidKind.LAVA ? z : nz;
                    int lVol = kind == FluidKind.LAVA ? vol : oppVol;

                    if (handleWaterLavaCollision(grid, wX, wY, wZ, wVol, lX, lY, lZ, lVol)) {
                        grid.enqueueNeighborsOnly(x, y, z, FluidKind.WATER);
                        grid.enqueueNeighborsOnly(x, y, z, FluidKind.LAVA);
                        grid.enqueueNeighborsOnly(nx, ny, nz, FluidKind.WATER);
                        grid.enqueueNeighborsOnly(nx, ny, nz, FluidKind.LAVA);
                        return true;
                    }
                }
            }
        }

        boolean gravity = kind == FluidKind.WATER ? config.waterGravityEnabled : config.lavaGravityEnabled;
        boolean equalize = kind == FluidKind.WATER ? config.waterEqualizeEnabled : config.lavaEqualizeEnabled;
        int minSpreadDiff = kind == FluidKind.WATER ? config.waterMinSpreadDiff : config.lavaMinSpreadDiff;
        boolean pressure = kind == FluidKind.WATER ? config.waterPressureEnabled : config.lavaPressureEnabled;

        // Phase 2: Gravity straight down
        if (gravity && y - 1 >= grid.minY() && grid.canAccess(x, y - 1, z)) {
            if (kind == FluidKind.WATER && config.unfreezeMarineFloraOnDrain && FluidGrid.isMarineFlora(grid.getMaterial(x, y - 1, z))) {
                grid.convertMarineFloraToWater(x, y - 1, z, config);
            }
            if (grid.canReceiveFluid(x, y - 1, z, kind, config)) {
                int belowVol = grid.getFluidLevelRaw(x, y - 1, z, kind);
                if (belowVol < 8) {
                    int move = Math.min(vol, 8 - belowVol);
                    grid.setFluidVolume(x, y, z, kind, vol - move, config);
                    grid.setFluidVolume(x, y - 1, z, kind, belowVol + move, config);
                    grid.enqueueNeighborsOnly(x, y, z, kind);
                    grid.enqueueNeighborsOnly(x, y - 1, z, kind);
                    return true;
                }
            } else if (kind == FluidKind.WATER && config.waterlogCapableBlocks && vol == 8 && grid.isEmptyWaterloggable(x, y - 1, z)) {
                grid.setWaterlogged(x, y - 1, z, true);
                grid.setFluidVolume(x, y, z, kind, 0, config);
                grid.enqueueNeighborsOnly(x, y, z, kind);
                grid.enqueueNeighborsOnly(x, y - 1, z, kind);
                return true;
            }
        }

        boolean changed = false;

        // Phase 3: Horizontal slope drain & equalizing
        if (equalize) {
            int offset = ThreadLocalRandom.current().nextInt(4);
            // 3A: Drop-off edge flow
            for (int i = 0; i < 4; i++) {
                int dir = (offset + i) & 3;
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                if (grid.isUnownedCrossRegion(nx, y, nz)) {
                    if (vol >= 2) {
                        int move = Math.max(1, vol / 2);
                        vol -= move;
                        grid.setFluidVolume(x, y, z, kind, vol, config);
                        grid.requestCrossRegionTransfer(x, y, z, nx, y, nz, kind, move);
                        grid.enqueueNeighborsOnly(x, y, z, kind);
                        return true;
                    }
                    continue;
                }
                if (!grid.canAccess(nx, y, nz)) {
                    continue;
                }
                if (kind == FluidKind.WATER && config.unfreezeMarineFloraOnDrain && FluidGrid.isMarineFlora(grid.getMaterial(nx, y, nz))) {
                    if (vol < 8 || (y - 1 >= grid.minY() && grid.canReceiveFluid(nx, y - 1, nz, kind, config))) {
                        grid.convertMarineFloraToWater(nx, y, nz, config);
                    }
                }
                if (!grid.canReceiveFluid(nx, y, nz, kind, config)) {
                    continue;
                }
                int nVol = grid.getFluidLevelRaw(nx, y, nz, kind);
                if (nVol >= 8 || nVol >= vol) {
                    continue;
                }
                if (y - 1 >= grid.minY() && grid.canAccess(nx, y - 1, nz)) {
                    boolean belowOpen = (grid.canReceiveFluid(nx, y - 1, nz, kind, config) && grid.getFluidLevelRaw(nx, y - 1, nz, kind) < 8)
                            || (kind == FluidKind.WATER && config.waterlogCapableBlocks && grid.isEmptyWaterloggable(nx, y - 1, nz));
                    if (belowOpen) {
                        int move = Math.min(8 - nVol, Math.max(1, (vol - nVol + 1) / 2));
                        if (move > vol) {
                            move = vol;
                        }
                        vol -= move;
                        grid.setFluidVolume(x, y, z, kind, vol, config);
                        grid.setFluidVolume(nx, y, nz, kind, nVol + move, config);
                        grid.enqueueNeighborsOnly(x, y, z, kind);
                        grid.enqueueNeighborsOnly(nx, y, nz, kind);
                        changed = true;
                        if (vol <= 0) {
                            return true;
                        }
                    }
                }
            }

            // 3B: Equalize across flat surfaces
            offset = ThreadLocalRandom.current().nextInt(4);
            for (int i = 0; i < 4; i++) {
                int dir = (offset + i) & 3;
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                if (!grid.canAccess(nx, y, nz)) {
                    continue;
                }
                if (!grid.canReceiveFluid(nx, y, nz, kind, config)) {
                    if (kind == FluidKind.WATER && config.waterlogCapableBlocks && vol == 8 && grid.isEmptyWaterloggable(nx, y, nz)
                            && y + 1 < grid.maxY() && grid.canAccess(x, y + 1, z) && grid.getFluidLevelRaw(x, y + 1, z, kind) > 0) {
                        grid.setWaterlogged(nx, y, nz, true);
                        grid.setFluidVolume(x, y, z, kind, 0, config);
                        grid.enqueueNeighborsOnly(x, y, z, kind);
                        grid.enqueueNeighborsOnly(nx, y, nz, kind);
                        return true;
                    }
                    continue;
                }
                int nVol = grid.getFluidLevelRaw(nx, y, nz, kind);
                if (vol - nVol >= minSpreadDiff) {
                    int move = Math.min(8 - nVol, Math.max(1, (vol - nVol) / 2));
                    vol -= move;
                    grid.setFluidVolume(x, y, z, kind, vol, config);
                    grid.setFluidVolume(nx, y, nz, kind, nVol + move, config);
                    grid.enqueueNeighborsOnly(x, y, z, kind);
                    grid.enqueueNeighborsOnly(nx, y, nz, kind);
                    changed = true;
                    if (vol <= 0) {
                        return true;
                    }
                }
            }

            // 3C: Thin puddle evaporation in hot/arid biomes
            if (!changed && kind == FluidKind.WATER && vol == 1 && config.evaporateThinPuddlesInDryBiomes) {
                if (grid.isHotDryCell(x, y, z)
                        && ThreadLocalRandom.current().nextDouble() < config.thinPuddleEvaporateChance) {
                    grid.setFluidVolume(x, y, z, FluidKind.WATER, 0, config);
                    grid.enqueueNeighborsOnly(x, y, z, FluidKind.WATER);
                    return true;
                }
            }
        }

        // Phase 4: Hydrostatic pressure push across connected vessels
        if (!changed && pressure && vol == 8 && y + 1 < grid.maxY() && grid.canAccess(x, y + 1, z)) {
            if (grid.getFluidLevelRaw(x, y + 1, z, kind) > 0) {
                if (tryHydrostaticPush(grid, x, y, z, kind)) {
                    return true;
                }
            }
        }

        return changed;
    }

    private boolean tryHydrostaticPush(FluidGrid grid, int startX, int startY, int startZ, FluidKind kind) {
        int maxDepth = kind == FluidKind.WATER ? config.waterPressureMaxDepth : config.lavaPressureMaxDepth;
        int maxVisited = kind == FluidKind.WATER ? config.waterPressureMaxVisited : config.lavaPressureMaxVisited;

        int topX = startX;
        int topY = startY;
        int topZ = startZ;
        int topVol = 8;

        ArrayDeque<Long> upBfs = new ArrayDeque<>();
        Set<Long> upVisited = new HashSet<>();
        long startPacked = LongPosSetQueue.pack(startX, startY, startZ);
        upBfs.add(startPacked);
        upVisited.add(startPacked);

        while (!upBfs.isEmpty() && upVisited.size() < 48) {
            long cur = upBfs.poll();
            int cx = LongPosSetQueue.unpackX(cur);
            int cy = LongPosSetQueue.unpackY(cur);
            int cz = LongPosSetQueue.unpackZ(cur);

            int[] checkOrderX = {0, 1, -1, 0, 0};
            int[] checkOrderY = {1, 0, 0, 0, 0};
            int[] checkOrderZ = {0, 0, 0, 1, -1};
            for (int i = 0; i < 5; i++) {
                int nx = cx + checkOrderX[i];
                int ny = cy + checkOrderY[i];
                int nz = cz + checkOrderZ[i];
                if (ny < startY || ny >= grid.maxY() || (ny - startY) > maxDepth || !grid.canAccess(nx, ny, nz)) {
                    continue;
                }
                long np = LongPosSetQueue.pack(nx, ny, nz);
                if (upVisited.contains(np) || grid.isWaterlogged(nx, ny, nz)) {
                    continue;
                }
                int nVol = grid.getFluidLevelRaw(nx, ny, nz, kind);
                if (nVol <= 0) {
                    continue;
                }
                upVisited.add(np);
                upBfs.add(np);
                if (ny > topY || (ny == topY && nVol > topVol)) {
                    topX = nx;
                    topY = ny;
                    topZ = nz;
                    topVol = nVol;
                }
            }
        }

        if (topY <= startY || grid.isWaterlogged(topX, topY, topZ)) {
            return false;
        }

        ArrayDeque<Long> bfs = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        bfs.add(startPacked);
        visited.add(startPacked);

        while (!bfs.isEmpty() && visited.size() < maxVisited) {
            long cur = bfs.poll();
            int cx = LongPosSetQueue.unpackX(cur);
            int cy = LongPosSetQueue.unpackY(cur);
            int cz = LongPosSetQueue.unpackZ(cur);

            for (int i = 0; i < 6; i++) {
                int nx = cx + DX6[i];
                int ny = cy + DY6[i];
                int nz = cz + DZ6[i];
                if (ny < grid.minY() || ny >= grid.maxY() || ny >= topY || Math.abs(ny - startY) > maxDepth) {
                    continue;
                }
                if (!grid.canAccess(nx, ny, nz)) {
                    continue;
                }
                long nextPacked = LongPosSetQueue.pack(nx, ny, nz);
                if (visited.contains(nextPacked)) {
                    continue;
                }
                if (grid.canReceiveFluid(nx, ny, nz, kind, config)) {
                    int nVol = grid.getFluidLevelRaw(nx, ny, nz, kind);
                    if (nVol < 8) {
                        int move = Math.min(topVol, Math.min(4, 8 - nVol));
                        grid.setFluidVolume(topX, topY, topZ, kind, topVol - move, config);
                        grid.setFluidVolume(nx, ny, nz, kind, nVol + move, config);
                        grid.enqueueNeighborsOnly(topX, topY, topZ, kind);
                        grid.enqueueNeighborsOnly(nx, ny, nz, kind);
                        return true;
                    }
                    visited.add(nextPacked);
                    bfs.add(nextPacked);
                }
            }
        }
        return false;
    }

    private boolean handleWaterLavaCollision(FluidGrid grid, int wX, int wY, int wZ, int waterVol,
                                             int lX, int lY, int lZ, int lavaVol) {
        if (waterVol <= 0 || lavaVol <= 0) {
            return false;
        }
        if (config.lavaWaterMode == PhysExConfig.LavaWaterMode.PHYSEX_EVAPORATE) {
            int evap = Math.min(waterVol, 4);
            grid.setFluidVolume(wX, wY, wZ, FluidKind.WATER, waterVol - evap, config);
            if (lavaVol == 8 && waterVol >= 6) {
                grid.setSolidResultMaterial(lX, lY, lZ, Material.OBSIDIAN);
            } else if (lavaVol <= 4) {
                grid.setSolidResultMaterial(lX, lY, lZ, Material.COBBLESTONE);
            }
            if (config.evaporateEffects) {
                grid.emitSteamEffect(wX, wY, wZ, 0.55f, 1.15f, 6);
            }
            return true;
        }

        if (config.lavaWaterMode == PhysExConfig.LavaWaterMode.CONSUME_WATER_STONE) {
            grid.setSolidResultMaterial(lX, lY, lZ, lavaVol == 8 ? Material.OBSIDIAN : Material.COBBLESTONE);
            grid.setFluidVolume(wX, wY, wZ, FluidKind.WATER, 0, config);
            if (config.evaporateEffects) {
                grid.emitSteamEffect(lX, lY, lZ, 0.5f, 1.0f, 5);
            }
            return true;
        }

        return false;
    }

    private boolean shouldUnfreezeMarineFlora(FluidGrid grid, int x, int y, int z) {
        if (y - 1 >= grid.minY() && grid.canAccess(x, y - 1, z)) {
            Material belowMat = grid.getMaterial(x, y - 1, z);
            if (!belowMat.isSolid() && !FluidGrid.isMarineFlora(belowMat)
                    && grid.getFluidLevelRaw(x, y - 1, z, FluidKind.WATER) < 8) {
                return true;
            }
        }
        for (int i = 0; i < 4; i++) {
            int nx = x + DX[i];
            int nz = z + DZ[i];
            if (!grid.canAccess(nx, y, nz)) {
                continue;
            }
            Material nm = grid.getMaterial(nx, y, nz);
            if (nm.isAir() || (nm == Material.WATER && grid.getFluidLevelRaw(nx, y, nz, FluidKind.WATER) <= 5)) {
                return true;
            }
        }
        return false;
    }

    private void wakeAdjacentMarineFlora(FluidGrid grid, int x, int y, int z) {
        for (int i = 0; i < 6; i++) {
            int ny = y + DY6[i];
            if (ny < grid.minY() || ny >= grid.maxY()) {
                continue;
            }
            int nx = x + DX6[i];
            int nz = z + DZ6[i];
            if (!grid.canAccess(nx, ny, nz)) {
                continue;
            }
            if (FluidGrid.isMarineFlora(grid.getMaterial(nx, ny, nz)) && shouldUnfreezeMarineFlora(grid, nx, ny, nz)) {
                grid.enqueueCell(nx, ny, nz, FluidKind.WATER);
            }
        }
    }

    // =========================================================================
    // CROSS-REGION FOLIA TRANSFERS, DISPLACEMENT, SCOOP & DIRECT HELPERS
    // =========================================================================

    void transferAcrossFoliaRegion(World world, int srcX, int srcY, int srcZ,
                                   int dstX, int dstY, int dstZ, FluidKind kind, int amount) {
        SchedulerBridge.runAtRegion(plugin, world, dstX >> 4, dstZ >> 4, () -> {
            if (!world.isChunkLoaded(dstX >> 4, dstZ >> 4)) {
                refundToSource(world, srcX, srcY, srcZ, kind, amount);
                return;
            }
            Block dst = world.getBlockAt(dstX, dstY, dstZ);
            if (!canReceiveFluid(dst, kind)) {
                refundToSource(world, srcX, srcY, srcZ, kind, amount);
                return;
            }
            int cur = getRawVolume(dst, kind);
            int accept = Math.min(amount, 8 - cur);
            if (accept > 0) {
                setVolume(dst, kind, cur + accept);
                enqueueNeighborsOnly(dst, kind);
            }
            int leftover = amount - accept;
            if (leftover > 0) {
                refundToSource(world, srcX, srcY, srcZ, kind, leftover);
            }
        });
    }

    private void refundToSource(World world, int srcX, int srcY, int srcZ, FluidKind kind, int amount) {
        if (amount <= 0) {
            return;
        }
        SchedulerBridge.runAtRegion(plugin, world, srcX >> 4, srcZ >> 4, () -> {
            if (!world.isChunkLoaded(srcX >> 4, srcZ >> 4)) {
                return;
            }
            Block src = world.getBlockAt(srcX, srcY, srcZ);
            int cur = getRawVolume(src, kind);
            if (canReceiveFluid(src, kind) && cur < 8) {
                int add = Math.min(amount, 8 - cur);
                setVolume(src, kind, cur + add);
                enqueueNeighborsOnly(src, kind);
                int stillLeft = amount - add;
                if (stillLeft > 0) {
                    displaceFluidVolume(src, kind, stillLeft);
                }
            } else {
                displaceFluidVolume(src, kind, amount);
            }
        });
    }

    public boolean displaceFluidVolume(Block origin, FluidKind kind, int volume) {
        if (volume <= 0 || origin == null) {
            return false;
        }
        World w = origin.getWorld();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        int remaining = volume;
        for (int r = 1; r <= 3 && remaining > 0; r++) {
            if (oy + r < w.getMaxHeight() && SchedulerBridge.canAccessBlockNow(w, ox, oz)) {
                Block up = w.getBlockAt(ox, oy + r, oz);
                remaining = injectIntoBlock(up, kind, remaining);
            }
            if (remaining <= 0) {
                break;
            }
            for (int dx = -r; dx <= r && remaining > 0; dx++) {
                for (int dz = -r; dz <= r && remaining > 0; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = ox + dx;
                    int nz = oz + dz;
                    if (!SchedulerBridge.canAccessBlockNow(w, nx, nz) || !w.isChunkLoaded(nx >> 4, nz >> 4)) {
                        continue;
                    }
                    int dyStart = Math.max(VersionCompat.getMinHeight(w), oy);
                    int dyEnd = Math.min(w.getMaxHeight() - 1, oy + r);
                    for (int y = dyStart; y <= dyEnd && remaining > 0; y++) {
                        Block target = w.getBlockAt(nx, y, nz);
                        remaining = injectIntoBlock(target, kind, remaining);
                    }
                }
            }
        }
        return remaining < volume;
    }

    private int injectIntoBlock(Block target, FluidKind kind, int amount) {
        if (amount <= 0) {
            return 0;
        }
        if (canReceiveFluid(target, kind)) {
            int cur = getRawVolume(target, kind);
            if (cur < 8) {
                int add = Math.min(amount, 8 - cur);
                setVolume(target, kind, cur + add);
                enqueueNeighborsOnly(target, kind);
                return amount - add;
            }
        } else if (kind == FluidKind.WATER && config.waterlogCapableBlocks && amount >= 8 && isEmptyWaterloggable(target)) {
            setWaterlogged(target, true);
            enqueueNeighborsOnly(target, kind);
            return amount - 8;
        }
        return amount;
    }

    public int tryScoopVolume(Block center, FluidKind kind, int needed) {
        World w = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        int totalFound = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int y = cy + dy;
            if (y < VersionCompat.getMinHeight(w) || y >= w.getMaxHeight()) {
                continue;
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (!SchedulerBridge.canAccessBlockNow(w, nx, nz) || !w.isChunkLoaded(nx >> 4, nz >> 4)) {
                        continue;
                    }
                    Block b = w.getBlockAt(nx, y, nz);
                    totalFound += getRawVolume(b, kind);
                    if (totalFound >= needed) {
                        break;
                    }
                }
                if (totalFound >= needed) {
                    break;
                }
            }
            if (totalFound >= needed) {
                break;
            }
        }

        if (totalFound < needed) {
            return 0;
        }

        int left = needed;
        left -= takeFromBlock(center, kind, left);
        for (int radius = 1; radius <= 2 && left > 0; radius++) {
            for (int dy = 1; dy >= -1 && left > 0; dy--) {
                int y = cy + dy;
                if (y < VersionCompat.getMinHeight(w) || y >= w.getMaxHeight()) {
                    continue;
                }
                for (int dx = -radius; dx <= radius && left > 0; dx++) {
                    for (int dz = -radius; dz <= radius && left > 0; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius && dy == 0) {
                            continue;
                        }
                        int nx = cx + dx;
                        int nz = cz + dz;
                        if (!SchedulerBridge.canAccessBlockNow(w, nx, nz) || !w.isChunkLoaded(nx >> 4, nz >> 4)) {
                            continue;
                        }
                        Block b = w.getBlockAt(nx, y, nz);
                        left -= takeFromBlock(b, kind, left);
                    }
                }
            }
        }
        return needed - left;
    }

    public int takeFromBlock(Block b, FluidKind kind, int amount) {
        int cur = getRawVolume(b, kind);
        if (cur <= 0 || amount <= 0) {
            return 0;
        }
        if (isWaterloggedState(b)) {
            if (kind == FluidKind.WATER && amount >= 8) {
                setWaterlogged(b, false);
                enqueueNeighborsOnly(b, FluidKind.WATER);
                return 8;
            }
            return 0;
        }
        int taken = Math.min(cur, amount);
        setVolume(b, kind, cur - taken);
        enqueueNeighborsOnly(b, kind);
        return taken;
    }

    public void drainRadiusAsync(Location center, int radius, FluidKind targetKind, java.util.function.IntConsumer callback) {
        runAcrossRadiusChunks(center, radius, true, targetKind, callback);
    }

    public void fillRadiusAsync(Location center, int radius, FluidKind targetKind, java.util.function.IntConsumer callback) {
        runAcrossRadiusChunks(center, radius, false, targetKind, callback);
    }

    private void runAcrossRadiusChunks(Location center, int radius, boolean drain, FluidKind kind,
                                       java.util.function.IntConsumer callback) {
        World w = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int minChunkX = (cx - radius) >> 4;
        int maxChunkX = (cx + radius) >> 4;
        int minChunkZ = (cz - radius) >> 4;
        int maxChunkZ = (cz + radius) >> 4;

        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        AtomicInteger remainingChunks = new AtomicInteger(totalChunks);
        AtomicInteger totalChanged = new AtomicInteger(0);

        for (int chX = minChunkX; chX <= maxChunkX; chX++) {
            for (int chZ = minChunkZ; chZ <= maxChunkZ; chZ++) {
                int chunkX = chX;
                int chunkZ = chZ;
                SchedulerBridge.runAtRegion(plugin, w, chunkX, chunkZ, () -> {
                    int changed = 0;
                    int xStart = Math.max(cx - radius, chunkX << 4);
                    int xEnd = Math.min(cx + radius, (chunkX << 4) + 15);
                    int zStart = Math.max(cz - radius, chunkZ << 4);
                    int zEnd = Math.min(cz + radius, (chunkZ << 4) + 15);
                    int yStart = Math.max(VersionCompat.getMinHeight(w), cy - radius);
                    int yEnd = Math.min(w.getMaxHeight() - 1, drain ? cy + radius : cy);

                    for (int x = xStart; x <= xEnd; x++) {
                        for (int z = zStart; z <= zEnd; z++) {
                            for (int y = yStart; y <= yEnd; y++) {
                                Block b = w.getBlockAt(x, y, z);
                                if (drain) {
                                    if (kind == null || kind == FluidKind.WATER) {
                                        if (b.getType() == Material.WATER || isWaterloggedState(b)) {
                                            setVolume(b, FluidKind.WATER, 0);
                                            changed++;
                                        }
                                    }
                                    if (kind == null || kind == FluidKind.LAVA) {
                                        if (b.getType() == Material.LAVA) {
                                            setVolume(b, FluidKind.LAVA, 0);
                                            changed++;
                                        }
                                    }
                                } else if (b.getType().isAir()) {
                                    setVolume(b, kind, 8);
                                    enqueue(w, x, y, z, kind);
                                    changed++;
                                }
                            }
                        }
                    }
                    int sum = totalChanged.addAndGet(changed);
                    if (remainingChunks.decrementAndGet() == 0 && callback != null) {
                        callback.accept(sum);
                    }
                });
            }
        }
    }

    private void tickRainPuddlesPaper() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (World world : Bukkit.getWorlds()) {
            if (!config.isWorldEnabled(world) || !world.hasStorm() || world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            for (int i = 0; i < config.rainFillAttemptsPerTick; i++) {
                Player p = players.get(rng.nextInt(players.size()));
                int rx = p.getLocation().getBlockX() + rng.nextInt(-32, 33);
                int rz = p.getLocation().getBlockZ() + rng.nextInt(-32, 33);
                applyRainDropAtColumn(world, rx, rz);
            }
        }
    }

    private void tickRainFoliaCoordinator() {
        if (!config.generalEnabled || paused || !config.rainFillsWorld || !config.waterEnabled || !config.waterFiniteMode) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            World w = player.getWorld();
            if (!config.isWorldEnabled(w) || !w.hasStorm() || w.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            SchedulerBridge.runAtEntity(plugin, player, () -> {
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                int rx = player.getLocation().getBlockX() + rng.nextInt(-32, 33);
                int rz = player.getLocation().getBlockZ() + rng.nextInt(-32, 33);
                SchedulerBridge.runAtRegion(plugin, w, rx >> 4, rz >> 4, () -> applyRainDropAtColumn(w, rx, rz));
            });
        }
    }

    private void applyRainDropAtColumn(World world, int rx, int rz) {
        if (!world.isChunkLoaded(rx >> 4, rz >> 4)) {
            return;
        }
        Block highest = world.getHighestBlockAt(rx, rz);
        Block above = highest.getRelative(BlockFace.UP);
        double temp = above.getTemperature();
        if (temp < 0.15 || temp > 0.95) {
            return;
        }
        if (above.getType().isAir() && highest.getType().isSolid() && highest.getType() != Material.ICE) {
            setVolume(above, FluidKind.WATER, 1);
            enqueueNeighborsOnly(above, FluidKind.WATER);
        } else if (highest.getType() == Material.WATER) {
            int v = getRawVolume(highest, FluidKind.WATER);
            if (v > 0 && v < 8) {
                setVolume(highest, FluidKind.WATER, v + 1);
                enqueueNeighborsOnly(highest, FluidKind.WATER);
            }
        }
    }

    public int getRawVolume(Block block, FluidKind kind) {
        return FluidGrid.readRawVolumeFromBlock(block, kind);
    }

    public void setVolume(Block block, FluidKind kind, int newVolume) {
        int clamped = Math.max(0, Math.min(8, newVolume));
        runInternalUpdate(() -> {
            if (kind == FluidKind.WATER && block.getBlockData() instanceof Waterlogged wl) {
                if (clamped == 0) {
                    wl.setWaterlogged(false);
                    block.setBlockData(wl, false);
                } else if (clamped >= 8) {
                    wl.setWaterlogged(true);
                    block.setBlockData(wl, false);
                }
                return;
            }

            if (clamped == 0) {
                if (block.getType() == kind.material) {
                    block.setType(Material.AIR, false);
                }
                return;
            }

            Material currentMat = block.getType();
            if (currentMat != kind.material && currentMat != Material.AIR) {
                boolean breakFragile = kind == FluidKind.WATER ? config.waterBreakFragileBlocks : config.lavaBreakFragileBlocks;
                if (breakFragile && FluidGrid.isFragileBreakable(currentMat)) {
                    if (kind == FluidKind.WATER) {
                        block.breakNaturally(false);
                    } else {
                        block.setType(Material.AIR, false);
                    }
                }
            }

            Levelled lv = (Levelled) Bukkit.createBlockData(kind.material);
            lv.setLevel(Math.max(0, Math.min(7, 8 - clamped)));
            block.setBlockData(lv, false);
        });
    }

    private void setWaterlogged(Block block, boolean state) {
        if (!(block.getBlockData() instanceof Waterlogged wl)) {
            return;
        }
        runInternalUpdate(() -> {
            wl.setWaterlogged(state);
            block.setBlockData(wl, false);
        });
    }

    public boolean canReceiveFluid(Block block, FluidKind kind) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type.isAir() || type == kind.material) {
            return true;
        }
        boolean breakFragile = kind == FluidKind.WATER ? config.waterBreakFragileBlocks : config.lavaBreakFragileBlocks;
        return breakFragile && FluidGrid.isFragileBreakable(type);
    }

    public boolean isWaterloggedState(Block block) {
        return block != null && block.getType() != Material.WATER
                && block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()
                && FluidGrid.isSafeWaterlogBlock(block.getBlockData());
    }

    private boolean isEmptyWaterloggable(Block block) {
        return block != null && block.getType() != Material.WATER
                && block.getBlockData() instanceof Waterlogged wl && !wl.isWaterlogged()
                && FluidGrid.isSafeWaterlogBlock(block.getBlockData());
    }

    public boolean isMarineFlora(Material mat) {
        return FluidGrid.isMarineFlora(mat);
    }

    // =========================================================================
    // QUEUE PERSISTENCE (INCLUDES IN-FLIGHT ASYNC ITEMS ON SHUTDOWN)
    // =========================================================================

    private void saveQueueState() {
        File file = new File(plugin.getDataFolder(), "queue_state.bin");
        Map<UUID, List<long[]>> groupedWater = new HashMap<>();
        Map<UUID, List<long[]>> groupedLava = new HashMap<>();

        if (!SchedulerBridge.isFolia()) {
            for (Map.Entry<UUID, LongPosSetQueue> e : paperWaterQueues.entrySet()) {
                groupedWater.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().snapshotArray());
            }
            for (Map.Entry<UUID, LongPosSetQueue> e : paperLavaQueues.entrySet()) {
                groupedLava.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().snapshotArray());
            }
            for (Map.Entry<UUID, List<BatchItem>> e : paperInFlightItems.entrySet()) {
                collectBatchIntoMaps(e.getKey(), e.getValue(), groupedWater, groupedLava);
            }
        } else {
            for (Map.Entry<RegionKey, RegionBucket> e : foliaBuckets.entrySet()) {
                UUID wid = e.getKey().worldId();
                RegionBucket b = e.getValue();
                synchronized (b) {
                    if (!b.waterQueue.isEmpty()) {
                        groupedWater.computeIfAbsent(wid, k -> new ArrayList<>()).add(b.waterQueue.snapshotArray());
                    }
                    if (!b.lavaQueue.isEmpty()) {
                        groupedLava.computeIfAbsent(wid, k -> new ArrayList<>()).add(b.lavaQueue.snapshotArray());
                    }
                    collectBatchIntoMaps(wid, b.inFlightWater, groupedWater, groupedLava);
                    collectBatchIntoMaps(wid, b.inFlightLava, groupedWater, groupedLava);
                }
            }
        }

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            writeGrouped(out, groupedWater);
            writeGrouped(out, groupedLava);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save queue_state.bin: " + ex.getMessage());
        }
    }

    private void collectBatchIntoMaps(UUID wid, List<BatchItem> items,
                                      Map<UUID, List<long[]>> groupedWater,
                                      Map<UUID, List<long[]>> groupedLava) {
        if (items == null || items.isEmpty()) {
            return;
        }
        long[] wArr = items.stream().filter(i -> i.kind() == FluidKind.WATER).mapToLong(BatchItem::packedPos).toArray();
        if (wArr.length > 0) {
            groupedWater.computeIfAbsent(wid, k -> new ArrayList<>()).add(wArr);
        }
        long[] lArr = items.stream().filter(i -> i.kind() == FluidKind.LAVA).mapToLong(BatchItem::packedPos).toArray();
        if (lArr.length > 0) {
            groupedLava.computeIfAbsent(wid, k -> new ArrayList<>()).add(lArr);
        }
    }

    private void writeGrouped(DataOutputStream out, Map<UUID, List<long[]>> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<UUID, List<long[]>> e : map.entrySet()) {
            out.writeLong(e.getKey().getMostSignificantBits());
            out.writeLong(e.getKey().getLeastSignificantBits());
            int total = 0;
            for (long[] arr : e.getValue()) {
                total += arr.length;
            }
            int writeCount = Math.min(total, 50_000);
            out.writeInt(writeCount);
            int written = 0;
            for (long[] arr : e.getValue()) {
                for (long v : arr) {
                    if (written >= writeCount) {
                        break;
                    }
                    out.writeLong(v);
                    written++;
                }
            }
        }
    }

    private void loadQueueState() {
        File file = new File(plugin.getDataFolder(), "queue_state.bin");
        if (!file.exists()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            readGrouped(in, FluidKind.WATER);
            readGrouped(in, FluidKind.LAVA);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not load queue_state.bin: " + ex.getMessage());
        } finally {
            file.delete();
        }
    }

    private void readGrouped(DataInputStream in, FluidKind kind) throws IOException {
        int worlds = in.readInt();
        for (int i = 0; i < worlds; i++) {
            UUID uid = new UUID(in.readLong(), in.readLong());
            int count = in.readInt();
            for (int j = 0; j < count; j++) {
                long packed = in.readLong();
                if (!SchedulerBridge.isFolia()) {
                    Map<UUID, LongPosSetQueue> map = kind == FluidKind.WATER ? paperWaterQueues : paperLavaQueues;
                    map.computeIfAbsent(uid, k -> new LongPosSetQueue(Math.max(256, count))).enqueuePacked(packed);
                } else {
                    int x = LongPosSetQueue.unpackX(packed);
                    int z = LongPosSetQueue.unpackZ(packed);
                    RegionKey rk = new RegionKey(uid, x >> 4, z >> 4);
                    RegionBucket b = foliaBuckets.computeIfAbsent(rk, k -> new RegionBucket());
                    b.queue(kind).enqueuePacked(packed);
                }
            }
        }
    }

    // =========================================================================
    // SYNCHRONOUS LIVE FLUID GRID ADAPTER (WHEN general.async-processing=false)
    // =========================================================================

    private final class LiveFluidGrid implements FluidGrid {
        private final World world;

        LiveFluidGrid(World world) {
            this.world = world;
        }

        @Override
        public int minY() {
            return VersionCompat.getMinHeight(world);
        }

        @Override
        public int maxY() {
            return world.getMaxHeight();
        }

        @Override
        public boolean isNether() {
            return world.getEnvironment() == World.Environment.NETHER;
        }

        @Override
        public boolean canAccess(int x, int y, int z) {
            return y >= VersionCompat.getMinHeight(world) && y < world.getMaxHeight()
                    && world.isChunkLoaded(x >> 4, z >> 4)
                    && SchedulerBridge.canAccessBlockNow(world, x, z);
        }

        @Override
        public boolean isUnownedCrossRegion(int x, int y, int z) {
            return y >= VersionCompat.getMinHeight(world) && y < world.getMaxHeight()
                    && world.isChunkLoaded(x >> 4, z >> 4)
                    && !SchedulerBridge.canAccessBlockNow(world, x, z);
        }

        @Override
        public void touch(int x, int y, int z) {
        }

        @Override
        public Material getMaterial(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType();
        }

        @Override
        public int getFluidLevelRaw(int x, int y, int z, FluidKind kind) {
            return getRawVolume(world.getBlockAt(x, y, z), kind);
        }

        @Override
        public int getVanillaLevel(int x, int y, int z) {
            BlockData data = world.getBlockAt(x, y, z).getBlockData();
            return data instanceof Levelled lv ? lv.getLevel() : -1;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType().isAir();
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType().isSolid();
        }

        @Override
        public boolean isWaterlogged(int x, int y, int z) {
            return isWaterloggedState(world.getBlockAt(x, y, z));
        }

        @Override
        public boolean isEmptyWaterloggable(int x, int y, int z) {
            return FluidSimulationEngine.this.isEmptyWaterloggable(world.getBlockAt(x, y, z));
        }

        @Override
        public boolean isHotDryCell(int x, int y, int z) {
            return isNether() || world.getBlockAt(x, y, z).getTemperature() >= 1.2;
        }

        @Override
        public Bisected.Half getBisectedHalf(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getBlockData() instanceof Bisected b ? b.getHalf() : null;
        }

        @Override
        public void setFluidVolume(int x, int y, int z, FluidKind kind, int newVolume, PhysExConfig config) {
            setVolume(world.getBlockAt(x, y, z), kind, newVolume);
        }

        @Override
        public void setWaterlogged(int x, int y, int z, boolean state) {
            FluidSimulationEngine.this.setWaterlogged(world.getBlockAt(x, y, z), state);
        }

        @Override
        public void setSolidResultMaterial(int x, int y, int z, Material resultMaterial) {
            runInternalUpdate(() -> world.getBlockAt(x, y, z).setType(resultMaterial, false));
        }

        @Override
        public void convertMarineFloraToWater(int x, int y, int z, PhysExConfig config) {
            Block block = world.getBlockAt(x, y, z);
            Material m = block.getType();
            runInternalUpdate(() -> {
                if (m == Material.TALL_SEAGRASS && block.getBlockData() instanceof Bisected bisected) {
                    Block partner = bisected.getHalf() == Bisected.Half.TOP
                            ? block.getRelative(BlockFace.DOWN)
                            : block.getRelative(BlockFace.UP);
                    if (partner.getType() == Material.TALL_SEAGRASS) {
                        Levelled lv = (Levelled) Bukkit.createBlockData(Material.WATER);
                        lv.setLevel(0);
                        partner.setBlockData(lv, false);
                        enqueue(world, partner.getX(), partner.getY(), partner.getZ(), FluidKind.WATER);
                    }
                }
                Levelled lv = (Levelled) Bukkit.createBlockData(Material.WATER);
                lv.setLevel(0);
                block.setBlockData(lv, false);
                if (m == Material.KELP || m == Material.KELP_PLANT) {
                    world.dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(Material.KELP, 1));
                }
            });
        }

        @Override
        public void emitSteamEffect(int x, int y, int z, float volume, float pitch, int count) {
            Location fx = new Location(world, x + 0.5, y + 0.5, z + 0.5);
            world.spawnParticle(Particle.CLOUD, fx, count, 0.25, 0.25, 0.25, 0.02);
            world.playSound(fx, Sound.BLOCK_LAVA_EXTINGUISH, volume, pitch);
        }

        @Override
        public void requestCrossRegionTransfer(int srcX, int srcY, int srcZ,
                                               int dstX, int dstY, int dstZ, FluidKind kind, int amount) {
            transferAcrossFoliaRegion(world, srcX, srcY, srcZ, dstX, dstY, dstZ, kind, amount);
        }

        @Override
        public void enqueueCell(int x, int y, int z, FluidKind kind) {
            enqueue(world, x, y, z, kind);
        }

        @Override
        public void enqueueNeighborsOnly(int x, int y, int z, FluidKind kind) {
            FluidSimulationEngine.this.enqueueNeighborsOnly(world, x, y, z, kind);
        }
    }

    private record RegionKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private static final class RegionBucket {
        final LongPosSetQueue waterQueue = new LongPosSetQueue(64);
        final LongPosSetQueue lavaQueue = new LongPosSetQueue(64);
        final AtomicBoolean waterScheduled = new AtomicBoolean(false);
        final AtomicBoolean lavaScheduled = new AtomicBoolean(false);
        final AtomicBoolean waterAsyncBusy = new AtomicBoolean(false);
        final AtomicBoolean lavaAsyncBusy = new AtomicBoolean(false);
        List<BatchItem> inFlightWater = null;
        List<BatchItem> inFlightLava = null;

        LongPosSetQueue queue(FluidKind kind) {
            return kind == FluidKind.WATER ? waterQueue : lavaQueue;
        }

        AtomicBoolean scheduledFlag(FluidKind kind) {
            return kind == FluidKind.WATER ? waterScheduled : lavaScheduled;
        }

        AtomicBoolean asyncFlag(FluidKind kind) {
            return kind == FluidKind.WATER ? waterAsyncBusy : lavaAsyncBusy;
        }

        void setInFlight(FluidKind kind, List<BatchItem> items) {
            if (kind == FluidKind.WATER) {
                inFlightWater = items;
            } else {
                inFlightLava = items;
            }
        }

        void clearInFlight(FluidKind kind) {
            if (kind == FluidKind.WATER) {
                inFlightWater = null;
            } else {
                inFlightLava = null;
            }
        }
    }
}
