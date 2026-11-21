package com.yunexal.physex;

import com.yunexal.physex.FluidSimulationEngine.FluidKind;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-contained async fluid batch solver and optimistic atomic diff applier.
 * <p>
 * Stage 2 (worker thread): reads immutable ChunkSnapshots and updates a virtual cell overlay,
 * linking causal multi-step chains into atomic transaction groups via union-find.
 * Stage 3 (main/region thread): verifies live blocks in each group still match snapshot
 * expectations before committing BlockData mutations, eliminating dupes or block loss.
 */
final class AsyncFluidBatch {

    record BatchItem(long packedPos, FluidKind kind) {
    }

    record WorldSnapshotContext(
            boolean nether,
            int minY,
            int maxY,
            Map<Long, ChunkSnapshot> snapshots,
            Set<Long> unownedLoadedChunks
    ) {
    }

    private record EffectHint(int stepId, int x, int y, int z, float volume, float pitch, int count) {
    }

    private record CrossTransfer(int stepId, int srcX, int srcY, int srcZ,
                                 int dstX, int dstY, int dstZ, FluidKind kind, int amount) {
    }

    private record EnqueueHint(int stepId, long packedPos, FluidKind kind) {
    }

    private static final class SimCell {
        final int x;
        final int y;
        final int z;
        final Material initMaterial;
        final int initRawLevel;
        final boolean initWaterlogged;
        final boolean initEmptyWaterloggable;
        final Bisected.Half initHalf;
        final boolean hotDry;

        Material material;
        int rawLevel;
        boolean waterlogged;
        boolean mutated = false;
        boolean breakFragileWithDrop = false;
        boolean dropKelp = false;
        FluidKind lastFluidKind = FluidKind.WATER;
        int groupStepId = -1;

        SimCell(int x, int y, int z, BlockData data, boolean hotDry) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.initMaterial = data.getMaterial();
            this.initRawLevel = data instanceof Levelled lv ? lv.getLevel() : -1;
            this.initEmptyWaterloggable = this.initMaterial != Material.WATER
                    && data instanceof Waterlogged wl
                    && !wl.isWaterlogged()
                    && FluidGrid.isSafeWaterlogBlock(data);
            this.initWaterlogged = this.initMaterial != Material.WATER
                    && data instanceof Waterlogged wl
                    && wl.isWaterlogged()
                    && FluidGrid.isSafeWaterlogBlock(data);
            this.initHalf = data instanceof Bisected b ? b.getHalf() : null;
            this.hotDry = hotDry;

            this.material = this.initMaterial;
            this.rawLevel = this.initRawLevel;
            this.waterlogged = this.initWaterlogged;
        }
    }

    private static final class TransactionGroup {
        final List<SimCell> mutations = new ArrayList<>();
        final List<EffectHint> effects = new ArrayList<>();
        final List<CrossTransfer> transfers = new ArrayList<>();
        final Set<Long> enqueueWater = new HashSet<>();
        final Set<Long> enqueueLava = new HashSet<>();
        final Set<Long> retryCoords = new HashSet<>();
    }

    static final class SolvedPlan {
        final List<TransactionGroup> groups;
        final double solveDurationMs;
        final int simulatedUpdates;

        SolvedPlan(List<TransactionGroup> groups, double solveDurationMs, int simulatedUpdates) {
            this.groups = groups;
            this.solveDurationMs = solveDurationMs;
            this.simulatedUpdates = simulatedUpdates;
        }
    }

    private AsyncFluidBatch() {
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
    }

    static SolvedPlan solveInBackground(WorldSnapshotContext ctx, List<BatchItem> items,
                                        PhysExConfig config, FluidSimulationEngine engine) {
        long startNs = System.nanoTime();
        SnapshotGrid grid = new SnapshotGrid(ctx, config);
        int updates = 0;

        for (BatchItem item : items) {
            int x = LongPosSetQueue.unpackX(item.packedPos());
            int y = LongPosSetQueue.unpackY(item.packedPos());
            int z = LongPosSetQueue.unpackZ(item.packedPos());
            if (!grid.canAccess(x, y, z)) {
                continue;
            }
            grid.beginStep(item.packedPos());
            if (engine.simulateStep(grid, x, y, z, item.kind())) {
                updates++;
            }
        }

        List<TransactionGroup> groups = grid.buildTransactionGroups();
        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        return new SolvedPlan(groups, elapsedMs, updates);
    }

    static int applyPlan(World world, SolvedPlan plan, FluidSimulationEngine engine, JavaPlugin plugin) {
        int appliedUpdates = 0;
        for (TransactionGroup group : plan.groups) {
            if (group.mutations.isEmpty() && group.transfers.isEmpty()) {
                continue;
            }
            if (!verifyGroup(world, group)) {
                for (long packed : group.retryCoords) {
                    int x = LongPosSetQueue.unpackX(packed);
                    int y = LongPosSetQueue.unpackY(packed);
                    int z = LongPosSetQueue.unpackZ(packed);
                    if (world.isChunkLoaded(x >> 4, z >> 4) && SchedulerBridge.canAccessBlockNow(world, x, z)) {
                        engine.enqueueBlockAndNeighbors(world.getBlockAt(x, y, z));
                    }
                }
                continue;
            }

            engine.runInternalUpdate(() -> {
                for (SimCell cell : group.mutations) {
                    Block block = world.getBlockAt(cell.x, cell.y, cell.z);
                    if (cell.initWaterlogged || cell.initEmptyWaterloggable) {
                        if (block.getBlockData() instanceof Waterlogged wl) {
                            wl.setWaterlogged(cell.waterlogged);
                            block.setBlockData(wl, false);
                        }
                        continue;
                    }
                    if (cell.breakFragileWithDrop && block.getType() != Material.WATER && !block.getType().isAir()) {
                        block.breakNaturally(false);
                    }
                    if (cell.dropKelp) {
                        world.dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(Material.KELP, 1));
                    }
                    if (cell.material == Material.AIR) {
                        block.setType(Material.AIR, false);
                    } else if (cell.material == Material.WATER || cell.material == Material.LAVA) {
                        Levelled lv = (Levelled) Bukkit.createBlockData(cell.material);
                        lv.setLevel(Math.max(0, Math.min(7, cell.rawLevel)));
                        block.setBlockData(lv, false);
                    } else {
                        block.setType(cell.material, false);
                    }
                }
            });

            if (engine.getConfig().evaporateEffects) {
                for (EffectHint fx : group.effects) {
                    Location loc = new Location(world, fx.x() + 0.5, fx.y() + 0.5, fx.z() + 0.5);
                    world.spawnParticle(Particle.CLOUD, loc, fx.count(), 0.25, 0.25, 0.25, 0.02);
                    world.playSound(loc, Sound.BLOCK_LAVA_EXTINGUISH, fx.volume(), fx.pitch());
                }
            }

            for (CrossTransfer tr : group.transfers) {
                engine.transferAcrossFoliaRegion(world, tr.srcX(), tr.srcY(), tr.srcZ(),
                        tr.dstX(), tr.dstY(), tr.dstZ(), tr.kind(), tr.amount());
            }

            for (long packed : group.enqueueWater) {
                engine.enqueue(world,
                        LongPosSetQueue.unpackX(packed),
                        LongPosSetQueue.unpackY(packed),
                        LongPosSetQueue.unpackZ(packed),
                        FluidKind.WATER);
            }
            for (long packed : group.enqueueLava) {
                engine.enqueue(world,
                        LongPosSetQueue.unpackX(packed),
                        LongPosSetQueue.unpackY(packed),
                        LongPosSetQueue.unpackZ(packed),
                        FluidKind.LAVA);
            }

            appliedUpdates += group.mutations.size();
        }
        return appliedUpdates;
    }

    private static boolean verifyGroup(World world, TransactionGroup group) {
        for (SimCell cell : group.mutations) {
            if (cell.y < VersionCompat.getMinHeight(world) || cell.y >= world.getMaxHeight()) {
                return false;
            }
            if (!world.isChunkLoaded(cell.x >> 4, cell.z >> 4) || !SchedulerBridge.canAccessBlockNow(world, cell.x, cell.z)) {
                return false;
            }
            Block live = world.getBlockAt(cell.x, cell.y, cell.z);
            if (live.getType() != cell.initMaterial) {
                return false;
            }
            if (cell.initRawLevel >= 0) {
                if (!(live.getBlockData() instanceof Levelled lv) || lv.getLevel() != cell.initRawLevel) {
                    return false;
                }
            }
            if (cell.initWaterlogged || cell.initEmptyWaterloggable) {
                if (!(live.getBlockData() instanceof Waterlogged wl) || wl.isWaterlogged() != cell.initWaterlogged) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class SnapshotGrid implements FluidGrid {

        private static final int[] DX6 = {1, -1, 0, 0, 0, 0};
        private static final int[] DY6 = {0, 0, 1, -1, 0, 0};
        private static final int[] DZ6 = {0, 0, 0, 0, 1, -1};

        private final WorldSnapshotContext ctx;
        private final PhysExConfig config;
        private final Map<Long, SimCell> cells = new HashMap<>();
        private final List<Integer> parent = new ArrayList<>();
        private final List<EffectHint> effects = new ArrayList<>();
        private final List<CrossTransfer> transfers = new ArrayList<>();
        private final List<EnqueueHint> enqueues = new ArrayList<>();
        private final List<Long> stepSeeds = new ArrayList<>();

        private int currentStepId = -1;

        SnapshotGrid(WorldSnapshotContext ctx, PhysExConfig config) {
            this.ctx = ctx;
            this.config = config;
        }

        void beginStep(long packedPos) {
            currentStepId = parent.size();
            parent.add(currentStepId);
            stepSeeds.add(packedPos);
        }

        private int find(int x) {
            int p = parent.get(x);
            while (p != parent.get(p)) {
                p = parent.get(p);
            }
            while (x != p) {
                int next = parent.get(x);
                parent.set(x, p);
                x = next;
            }
            return p;
        }

        private void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra != rb) {
                parent.set(rb, ra);
            }
        }

        private SimCell cellAt(int x, int y, int z) {
            long packed = LongPosSetQueue.pack(x, y, z);
            SimCell existing = cells.get(packed);
            if (existing != null) {
                return existing;
            }
            ChunkSnapshot snap = ctx.snapshots().get(chunkKey(x >> 4, z >> 4));
            if (snap == null || y < ctx.minY() || y >= ctx.maxY()) {
                return null;
            }
            int lx = x & 15;
            int lz = z & 15;
            BlockData data = snap.getBlockData(lx, y, lz);
            boolean hotDry = ctx.nether();
            if (!hotDry && config.evaporateThinPuddlesInDryBiomes) {
                hotDry = snap.getRawBiomeTemperature(lx, y, lz) >= 1.2;
            }
            SimCell created = new SimCell(x, y, z, data, hotDry);
            cells.put(packed, created);
            return created;
        }

        @Override
        public int minY() {
            return ctx.minY();
        }

        @Override
        public int maxY() {
            return ctx.maxY();
        }

        @Override
        public boolean isNether() {
            return ctx.nether();
        }

        @Override
        public boolean canAccess(int x, int y, int z) {
            return y >= ctx.minY() && y < ctx.maxY() && ctx.snapshots().containsKey(chunkKey(x >> 4, z >> 4));
        }

        @Override
        public boolean isUnownedCrossRegion(int x, int y, int z) {
            return y >= ctx.minY() && y < ctx.maxY() && ctx.unownedLoadedChunks().contains(chunkKey(x >> 4, z >> 4));
        }

        @Override
        public void touch(int x, int y, int z) {
            if (currentStepId < 0) {
                return;
            }
            SimCell c = cellAt(x, y, z);
            if (c == null) {
                return;
            }
            if (c.groupStepId < 0) {
                c.groupStepId = currentStepId;
            } else {
                union(currentStepId, c.groupStepId);
            }
        }

        @Override
        public Material getMaterial(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c == null ? Material.AIR : c.material;
        }

        @Override
        public int getFluidLevelRaw(int x, int y, int z, FluidKind kind) {
            SimCell c = cellAt(x, y, z);
            if (c == null) {
                return 0;
            }
            if (c.material == kind.material && c.rawLevel >= 0) {
                return c.rawLevel >= 8 ? 2 : Math.max(0, 8 - c.rawLevel);
            }
            if (kind == FluidKind.WATER && isWaterlogged(x, y, z)) {
                return 8;
            }
            return 0;
        }

        @Override
        public int getVanillaLevel(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c == null ? -1 : c.rawLevel;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c != null && c.material.isAir();
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c != null && c.material.isSolid();
        }

        @Override
        public boolean isWaterlogged(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c != null && c.material != Material.WATER && c.waterlogged;
        }

        @Override
        public boolean isEmptyWaterloggable(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c != null && c.initEmptyWaterloggable && !c.waterlogged;
        }

        @Override
        public boolean isHotDryCell(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c != null && c.hotDry;
        }

        @Override
        public Bisected.Half getBisectedHalf(int x, int y, int z) {
            SimCell c = cellAt(x, y, z);
            return c == null ? null : c.initHalf;
        }

        @Override
        public void setFluidVolume(int x, int y, int z, FluidKind kind, int newVolume, PhysExConfig config) {
            SimCell c = cellAt(x, y, z);
            if (c == null) {
                return;
            }
            touch(x, y, z);
            c.mutated = true;
            c.lastFluidKind = kind;
            int clamped = Math.max(0, Math.min(8, newVolume));

            if (kind == FluidKind.WATER && (c.initWaterlogged || c.initEmptyWaterloggable)) {
                if (clamped == 0) {
                    c.waterlogged = false;
                } else if (clamped >= 8) {
                    c.waterlogged = true;
                }
                return;
            }

            if (clamped == 0) {
                if (c.material == kind.material) {
                    c.material = Material.AIR;
                    c.rawLevel = -1;
                }
                return;
            }

            if (c.material != kind.material && !c.material.isAir()) {
                boolean breakFragile = kind == FluidKind.WATER ? config.waterBreakFragileBlocks : config.lavaBreakFragileBlocks;
                if (breakFragile && FluidGrid.isFragileBreakable(c.material) && kind == FluidKind.WATER) {
                    c.breakFragileWithDrop = true;
                }
            }
            c.material = kind.material;
            c.rawLevel = Math.max(0, Math.min(7, 8 - clamped));
        }

        @Override
        public void setWaterlogged(int x, int y, int z, boolean state) {
            SimCell c = cellAt(x, y, z);
            if (c == null || (!c.initWaterlogged && !c.initEmptyWaterloggable)) {
                return;
            }
            touch(x, y, z);
            c.mutated = true;
            c.waterlogged = state;
        }

        @Override
        public void setSolidResultMaterial(int x, int y, int z, Material resultMaterial) {
            SimCell c = cellAt(x, y, z);
            if (c == null) {
                return;
            }
            touch(x, y, z);
            c.mutated = true;
            c.material = resultMaterial;
            c.rawLevel = -1;
            c.waterlogged = false;
        }

        @Override
        public void convertMarineFloraToWater(int x, int y, int z, PhysExConfig config) {
            SimCell c = cellAt(x, y, z);
            if (c == null) {
                return;
            }
            Material prevMat = c.material;
            touch(x, y, z);
            if (prevMat == Material.TALL_SEAGRASS && c.initHalf != null) {
                int py = c.initHalf == Bisected.Half.TOP ? y - 1 : y + 1;
                if (canAccess(x, py, z) && getMaterial(x, py, z) == Material.TALL_SEAGRASS) {
                    SimCell partner = cellAt(x, py, z);
                    if (partner != null) {
                        touch(x, py, z);
                        partner.mutated = true;
                        partner.material = Material.WATER;
                        partner.rawLevel = 0;
                        enqueueCell(x, py, z, FluidKind.WATER);
                    }
                }
            }
            c.mutated = true;
            c.material = Material.WATER;
            c.rawLevel = 0;
            if (prevMat == Material.KELP || prevMat == Material.KELP_PLANT) {
                c.dropKelp = true;
            }
        }

        @Override
        public void emitSteamEffect(int x, int y, int z, float volume, float pitch, int count) {
            if (currentStepId >= 0) {
                effects.add(new EffectHint(currentStepId, x, y, z, volume, pitch, count));
            }
        }

        @Override
        public void requestCrossRegionTransfer(int srcX, int srcY, int srcZ,
                                               int dstX, int dstY, int dstZ, FluidKind kind, int amount) {
            if (currentStepId >= 0) {
                transfers.add(new CrossTransfer(currentStepId, srcX, srcY, srcZ, dstX, dstY, dstZ, kind, amount));
            }
        }

        @Override
        public void enqueueCell(int x, int y, int z, FluidKind kind) {
            if (currentStepId >= 0) {
                enqueues.add(new EnqueueHint(currentStepId, LongPosSetQueue.pack(x, y, z), kind));
            }
        }

        @Override
        public void enqueueNeighborsOnly(int x, int y, int z, FluidKind kind) {
            enqueueCell(x, y, z, kind);
            for (int i = 0; i < 6; i++) {
                enqueueCell(x + DX6[i], y + DY6[i], z + DZ6[i], kind);
            }
        }

        List<TransactionGroup> buildTransactionGroups() {
            Map<Integer, TransactionGroup> map = new HashMap<>();

            for (SimCell c : cells.values()) {
                if (!c.mutated || c.groupStepId < 0) {
                    continue;
                }
                int root = find(c.groupStepId);
                TransactionGroup g = map.computeIfAbsent(root, k -> new TransactionGroup());
                g.mutations.add(c);
                g.retryCoords.add(LongPosSetQueue.pack(c.x, c.y, c.z));
            }

            for (EffectHint fx : effects) {
                TransactionGroup g = map.get(find(fx.stepId()));
                if (g != null) {
                    g.effects.add(fx);
                }
            }

            for (CrossTransfer tr : transfers) {
                TransactionGroup g = map.get(find(tr.stepId()));
                if (g != null) {
                    g.transfers.add(tr);
                }
            }

            for (EnqueueHint eh : enqueues) {
                TransactionGroup g = map.get(find(eh.stepId()));
                if (g == null) {
                    continue;
                }
                if (eh.kind() == FluidKind.WATER) {
                    g.enqueueWater.add(eh.packedPos());
                } else {
                    g.enqueueLava.add(eh.packedPos());
                }
            }

            return new ArrayList<>(map.values());
        }
    }
}
