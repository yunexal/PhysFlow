package com.yunexal.physex;

import com.yunexal.physex.FluidSimulationEngine.FluidKind;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Region-safe Bukkit/Paper/Folia event bridge for fluid displacement, pistons,
 * buckets, glass bottles, farmland hydration, dripstone, ice melt & explosions.
 */
public class FluidListeners implements Listener {

    private final JavaPlugin plugin;
    private final PhysExConfig config;
    private final FluidSimulationEngine engine;

    public FluidListeners(JavaPlugin plugin, PhysExConfig config, FluidSimulationEngine engine) {
        this.plugin = plugin;
        this.config = config;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block from = event.getBlock();
        World world = from.getWorld();
        if (!config.isWorldEnabled(world)) {
            return;
        }
        Material mat = from.getType();
        if (mat == Material.WATER && config.waterEnabled && config.waterFiniteMode) {
            event.setCancelled(true);
            engine.enqueue(world, from.getX(), from.getY(), from.getZ(), FluidKind.WATER);
            engine.enqueueNeighborsOnly(from, FluidKind.WATER);
        } else if (mat == Material.LAVA && config.lavaEnabled && config.lavaFiniteMode) {
            event.setCancelled(true);
            engine.enqueue(world, from.getX(), from.getY(), from.getZ(), FluidKind.LAVA);
            engine.enqueueNeighborsOnly(from, FluidKind.LAVA);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        if (engine.isInternalUpdate()) {
            return;
        }
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!config.isWorldEnabled(world)) {
            return;
        }
        Material mat = block.getType();
        Material newMat = event.getNewData().getMaterial();

        if ((mat == Material.WATER || newMat == Material.WATER) && config.waterEnabled && config.waterFiniteMode) {
            event.setCancelled(true);
            engine.enqueue(world, block.getX(), block.getY(), block.getZ(), FluidKind.WATER);
            engine.enqueueNeighborsOnly(block, FluidKind.WATER);
        } else if ((mat == Material.LAVA || newMat == Material.LAVA) && config.lavaEnabled && config.lavaFiniteMode) {
            event.setCancelled(true);
            engine.enqueue(world, block.getX(), block.getY(), block.getZ(), FluidKind.LAVA);
            engine.enqueueNeighborsOnly(block, FluidKind.LAVA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!config.isWorldEnabled(b.getWorld())) {
            return;
        }
        SchedulerBridge.runAtBlockLater(plugin, b, () -> engine.enqueueBlockAndNeighbors(b), 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (!config.isWorldEnabled(placed.getWorld())) {
            return;
        }
        BlockState replaced = event.getBlockReplacedState();
        Material replacedType = replaced.getType();

        FluidKind displacedKind = null;
        int displacedVol = 0;

        if (replacedType == Material.WATER && config.waterEnabled && config.waterFiniteMode && config.waterDisplaceOnPlace) {
            displacedKind = FluidKind.WATER;
            if (replaced.getBlockData() instanceof Levelled lv) {
                displacedVol = lv.getLevel() >= 8 ? 2 : (8 - lv.getLevel());
            }
        } else if (replacedType == Material.LAVA && config.lavaEnabled && config.lavaFiniteMode && config.lavaDisplaceOnPlace) {
            displacedKind = FluidKind.LAVA;
            if (replaced.getBlockData() instanceof Levelled lv) {
                displacedVol = lv.getLevel() >= 8 ? 2 : (8 - lv.getLevel());
            }
        }

        if (displacedKind != null && displacedVol > 0) {
            BlockData newData = placed.getBlockData();
            boolean stayedWaterlogged = newData instanceof Waterlogged wl && wl.isWaterlogged();
            if (!stayedWaterlogged) {
                FluidKind finalKind = displacedKind;
                int finalVol = displacedVol;
                SchedulerBridge.runAtBlockLater(plugin, placed, () -> {
                    if (!placed.getType().isAir()) {
                        engine.displaceFluidVolume(placed, finalKind, finalVol);
                    }
                    engine.enqueueBlockAndNeighbors(placed);
                }, 1L);
                return;
            }
        }
        SchedulerBridge.runAtBlockLater(plugin, placed, () -> engine.enqueueBlockAndNeighbors(placed), 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!config.isWorldEnabled(event.getBlock().getWorld())) {
            return;
        }
        handlePistonFluidPush(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!config.isWorldEnabled(event.getBlock().getWorld())) {
            return;
        }
        handlePistonFluidPush(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    private void handlePistonFluidPush(Block piston, List<Block> movedBlocks, BlockFace dir) {
        List<DisplacedSnapshot> captures = new ArrayList<>();
        captureIfDisplaceable(piston.getRelative(dir), captures);
        for (Block mb : movedBlocks) {
            captureIfDisplaceable(mb.getRelative(dir), captures);
        }

        SchedulerBridge.runAtBlockLater(plugin, piston, () -> {
            for (DisplacedSnapshot snap : captures) {
                Block dest = snap.target().getRelative(dir);
                SchedulerBridge.runAtRegion(plugin, dest.getWorld(), dest.getX() >> 4, dest.getZ() >> 4, () -> {
                    if (!engine.displaceFluidVolume(dest, snap.kind(), snap.volume())) {
                        SchedulerBridge.runAtRegion(plugin, snap.target().getWorld(),
                                snap.target().getX() >> 4, snap.target().getZ() >> 4,
                                () -> engine.displaceFluidVolume(snap.target(), snap.kind(), snap.volume()));
                    }
                });
            }
            engine.enqueueBlockAndNeighbors(piston);
            for (Block mb : movedBlocks) {
                SchedulerBridge.runAtRegion(plugin, mb.getWorld(), mb.getX() >> 4, mb.getZ() >> 4,
                        () -> engine.enqueueBlockAndNeighbors(mb));
                Block movedDest = mb.getRelative(dir);
                SchedulerBridge.runAtRegion(plugin, movedDest.getWorld(), movedDest.getX() >> 4, movedDest.getZ() >> 4,
                        () -> engine.enqueueBlockAndNeighbors(movedDest));
            }
        }, 1L);
    }

    private void captureIfDisplaceable(Block target, List<DisplacedSnapshot> captures) {
        Material m = target.getType();
        if (m == Material.WATER && config.waterEnabled && config.waterDisplaceOnPiston) {
            int vol = engine.getRawVolume(target, FluidKind.WATER);
            if (vol > 0) {
                captures.add(new DisplacedSnapshot(target, FluidKind.WATER, vol));
            }
        } else if (m == Material.LAVA && config.lavaEnabled && config.lavaDisplaceOnPiston) {
            int vol = engine.getRawVolume(target, FluidKind.LAVA);
            if (vol > 0) {
                captures.add(new DisplacedSnapshot(target, FluidKind.LAVA, vol));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        if (!config.isWorldEnabled(block.getWorld())) {
            return;
        }
        Material bucket = event.getBucket();
        SchedulerBridge.runAtBlockLater(plugin, block, () -> {
            if (bucket == Material.WATER_BUCKET) {
                engine.enqueueNeighborsOnly(block, FluidKind.WATER);
            } else if (bucket == Material.LAVA_BUCKET) {
                engine.enqueueNeighborsOnly(block, FluidKind.LAVA);
            } else {
                engine.enqueueBlockAndNeighbors(block);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        if (!config.isWorldEnabled(block.getWorld())) {
            return;
        }
        SchedulerBridge.runAtBlockLater(plugin, block, () -> engine.enqueueBlockAndNeighbors(block), 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR)) {
            return;
        }
        Player player = event.getPlayer();
        if (!config.isWorldEnabled(player.getWorld())) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getType() == Material.GLASS_BOTTLE && config.consumeOnBottleFill && config.waterEnabled && config.waterFiniteMode) {
            RayTraceResult ray = player.getWorld().rayTraceBlocks(
                    player.getEyeLocation(), player.getEyeLocation().getDirection(), 5.0,
                    FluidCollisionMode.ALWAYS, false);
            if (ray != null && ray.getHitBlock() != null) {
                Block hit = ray.getHitBlock();
                if (!SchedulerBridge.canAccessBlockNow(hit.getWorld(), hit.getX(), hit.getZ())) {
                    return;
                }
                int vol = engine.getRawVolume(hit, FluidKind.WATER);
                if (vol > 0) {
                    event.setCancelled(true);
                    int taken = engine.tryScoopVolume(hit, FluidKind.WATER, Math.min(config.bottleVolumeCost, 8));
                    if (taken == 0) {
                        engine.takeFromBlock(hit, FluidKind.WATER, config.bottleVolumeCost);
                    }
                    giveWaterBottle(player, hand);
                    player.getWorld().playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 1.0f, 1.0f);
                }
            }
            return;
        }

        if (hand.getType() == Material.BUCKET) {
            RayTraceResult ray = player.getWorld().rayTraceBlocks(
                    player.getEyeLocation(), player.getEyeLocation().getDirection(), 5.0,
                    FluidCollisionMode.ALWAYS, false);
            if (ray == null || ray.getHitBlock() == null) {
                return;
            }
            Block hit = ray.getHitBlock();
            if (!SchedulerBridge.canAccessBlockNow(hit.getWorld(), hit.getX(), hit.getZ())) {
                return;
            }
            if (hit.getType() == Material.WATER && config.waterEnabled && config.waterBucketScoopPartial) {
                int raw = engine.getRawVolume(hit, FluidKind.WATER);
                if (raw > 0 && raw < 8) {
                    int scooped = engine.tryScoopVolume(hit, FluidKind.WATER, 8);
                    if (scooped >= 8) {
                        event.setCancelled(true);
                        swapBucketForFilled(player, hand, Material.WATER_BUCKET);
                        player.getWorld().playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL, 1.0f, 1.0f);
                    }
                }
            } else if (hit.getType() == Material.LAVA && config.lavaEnabled && config.lavaBucketScoopPartial) {
                int raw = engine.getRawVolume(hit, FluidKind.LAVA);
                if (raw > 0 && raw < 8) {
                    int scooped = engine.tryScoopVolume(hit, FluidKind.LAVA, 8);
                    if (scooped >= 8) {
                        event.setCancelled(true);
                        swapBucketForFilled(player, hand, Material.LAVA_BUCKET);
                        player.getWorld().playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 1.0f, 1.0f);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMoistureChange(MoistureChangeEvent event) {
        if (!config.waterEnabled || !config.waterFiniteMode || !config.consumeOnFarmlandHydrate) {
            return;
        }
        Block soil = event.getBlock();
        if (!config.isWorldEnabled(soil.getWorld())) {
            return;
        }
        if (!(soil.getBlockData() instanceof Farmland oldFarm) || !(event.getNewState().getBlockData() instanceof Farmland newFarm)) {
            return;
        }
        if (newFarm.getMoisture() > oldFarm.getMoisture() && Math.random() < config.farmlandConsumeChance) {
            World w = soil.getWorld();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int nx = soil.getX() + dx;
                    int nz = soil.getZ() + dz;
                    if (!SchedulerBridge.canAccessBlockNow(w, nx, nz) || !w.isChunkLoaded(nx >> 4, nz >> 4)) {
                        continue;
                    }
                    Block water = w.getBlockAt(nx, soil.getY(), nz);
                    if (engine.getRawVolume(water, FluidKind.WATER) > 0) {
                        engine.takeFromBlock(water, FluidKind.WATER, 1);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCauldronChange(CauldronLevelChangeEvent event) {
        if (!config.isWorldEnabled(event.getBlock().getWorld())) {
            return;
        }
        if (!"NATURAL_FILL".equals(event.getReason().name())) {
            return;
        }
        Material nextMat = event.getNewState().getType();
        if ((VersionCompat.WATER_CAULDRON != null && nextMat == VersionCompat.WATER_CAULDRON && config.waterDisableDripstone)
                || (VersionCompat.LAVA_CAULDRON != null && nextMat == VersionCompat.LAVA_CAULDRON && config.lavaDisableDripstone)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        if (!config.meltingIceCreatesFiniteWater || !config.waterEnabled || !config.waterFiniteMode) {
            return;
        }
        Block block = event.getBlock();
        if (!config.isWorldEnabled(block.getWorld())) {
            return;
        }
        if (event.getNewState().getType() == Material.WATER) {
            SchedulerBridge.runAtBlockLater(plugin, block, () -> engine.enqueueBlockAndNeighbors(block), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (!config.isWorldEnabled(event.getBlock().getWorld())) {
            return;
        }
        List<BlockState> absorbed = event.getBlocks();
        Block sponge = event.getBlock();
        SchedulerBridge.runAtBlockLater(plugin, sponge, () -> engine.enqueueBlockAndNeighbors(sponge), 1L);
        for (BlockState bs : absorbed) {
            Block b = bs.getBlock();
            SchedulerBridge.runAtBlockLater(plugin, b, () -> engine.enqueueBlockAndNeighbors(b), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getLocation().getWorld() == null || !config.isWorldEnabled(event.getLocation().getWorld())) {
            return;
        }
        for (Block b : new ArrayList<>(event.blockList())) {
            SchedulerBridge.runAtBlockLater(plugin, b, () -> engine.enqueueBlockAndNeighbors(b), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!config.isWorldEnabled(event.getBlock().getWorld())) {
            return;
        }
        for (Block b : new ArrayList<>(event.blockList())) {
            SchedulerBridge.runAtBlockLater(plugin, b, () -> engine.enqueueBlockAndNeighbors(b), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (engine.isInternalUpdate()) {
            return;
        }
        Block b = event.getBlock();
        if (!config.isWorldEnabled(b.getWorld())) {
            return;
        }
        Material t = b.getType();
        if (t == Material.WATER && config.waterEnabled && config.waterFiniteMode) {
            if (hasOpenOrPartialNeighbor(b, FluidKind.WATER)) {
                engine.enqueue(b.getWorld(), b.getX(), b.getY(), b.getZ(), FluidKind.WATER);
            }
        } else if (t == Material.LAVA && config.lavaEnabled && config.lavaFiniteMode) {
            if (hasOpenOrPartialNeighbor(b, FluidKind.LAVA)) {
                engine.enqueue(b.getWorld(), b.getX(), b.getY(), b.getZ(), FluidKind.LAVA);
            }
        }
    }

    private boolean hasOpenOrPartialNeighbor(Block center, FluidKind kind) {
        if (engine.getRawVolume(center, kind) < 8) {
            return true;
        }
        BlockFace[] faces = {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        World w = center.getWorld();
        for (BlockFace f : faces) {
            int nx = center.getX() + f.getModX();
            int nz = center.getZ() + f.getModZ();
            if (!SchedulerBridge.canAccessBlockNow(w, nx, nz)) {
                continue;
            }
            Block rel = center.getRelative(f);
            if (engine.canReceiveFluid(rel, kind) && engine.getRawVolume(rel, kind) < 8) {
                return true;
            }
        }
        return false;
    }

    private void giveWaterBottle(Player player, ItemStack hand) {
        ItemStack bottle = new ItemStack(Material.POTION, 1);
        PotionMeta meta = (PotionMeta) bottle.getItemMeta();
        if (meta != null) {
            meta.setBasePotionType(PotionType.WATER);
            bottle.setItemMeta(meta);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(bottle);
        for (ItemStack rem : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rem);
        }
    }

    private void swapBucketForFilled(Player player, ItemStack hand, Material filledBucket) {
        ItemStack result = new ItemStack(filledBucket, 1);
        if (player.getGameMode() == GameMode.CREATIVE) {
            player.getInventory().addItem(result);
            return;
        }
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(result);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            Map<Integer, ItemStack> left = player.getInventory().addItem(result);
            for (ItemStack rem : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rem);
            }
        }
    }

    private record DisplacedSnapshot(Block target, FluidKind kind, int volume) {
    }
}
