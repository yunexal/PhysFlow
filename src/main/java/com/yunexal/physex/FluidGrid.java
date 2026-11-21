package com.yunexal.physex;

import com.yunexal.physex.FluidSimulationEngine.FluidKind;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.Wall;

import java.util.Set;

/**
 * Abstract cell-level view over either an off-thread ChunkSnapshot overlay (background processing)
 * or a live World/Region (synchronous fallback).
 */
interface FluidGrid {

    Set<Material> FRAGILE_BREAKABLE = Set.of(
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.DEAD_BUSH, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
            Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER,
            Material.LILY_OF_THE_VALLEY, Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH,
            Material.SOUL_WALL_TORCH, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.REDSTONE_WIRE, Material.SNOW, Material.COBWEB, Material.WHEAT,
            Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.SUGAR_CANE,
            Material.FIRE, Material.SOUL_FIRE, Material.VINE, Material.GLOW_LICHEN,
            Material.PINK_PETALS, Material.BROWN_MUSHROOM,
            Material.RED_MUSHROOM, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS
    );

    Set<Material> MARINE_FLORA = Set.of(
            Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.BUBBLE_COLUMN
    );

    int minY();

    int maxY();

    boolean isNether();

    boolean canAccess(int x, int y, int z);

    boolean isUnownedCrossRegion(int x, int y, int z);

    void touch(int x, int y, int z);

    Material getMaterial(int x, int y, int z);

    int getFluidLevelRaw(int x, int y, int z, FluidKind kind);

    /**
     * Reads liquid level and normalizes vanilla falling level>=8 cells in-place.
     */
    default int normalizeAndReadVolume(int x, int y, int z, FluidKind kind, PhysExConfig config) {
        Material mat = getMaterial(x, y, z);
        if (mat == kind.material) {
            int level = getVanillaLevel(x, y, z);
            if (level >= 8) {
                int norm = (y + 1 < maxY() && canAccess(x, y + 1, z) && getFluidLevelRaw(x, y + 1, z, kind) > 0) ? 2 : 1;
                setFluidVolume(x, y, z, kind, norm, config);
                return norm;
            }
            return level >= 0 ? Math.max(0, 8 - level) : 0;
        }
        if (kind == FluidKind.WATER && isWaterlogged(x, y, z)) {
            return 8;
        }
        return 0;
    }

    int getVanillaLevel(int x, int y, int z);

    boolean isAir(int x, int y, int z);

    boolean isSolid(int x, int y, int z);

    boolean isWaterlogged(int x, int y, int z);

    boolean isEmptyWaterloggable(int x, int y, int z);

    boolean isHotDryCell(int x, int y, int z);

    Bisected.Half getBisectedHalf(int x, int y, int z);

    default boolean canReceiveFluid(int x, int y, int z, FluidKind kind, PhysExConfig config) {
        if (!canAccess(x, y, z)) {
            return false;
        }
        Material mat = getMaterial(x, y, z);
        if (mat.isAir() || mat == kind.material) {
            return true;
        }
        boolean breakFragile = kind == FluidKind.WATER ? config.waterBreakFragileBlocks : config.lavaBreakFragileBlocks;
        return breakFragile && isFragileBreakable(mat);
    }

    void setFluidVolume(int x, int y, int z, FluidKind kind, int newVolume, PhysExConfig config);

    void setWaterlogged(int x, int y, int z, boolean state);

    void setSolidResultMaterial(int x, int y, int z, Material resultMaterial);

    void convertMarineFloraToWater(int x, int y, int z, PhysExConfig config);

    void emitSteamEffect(int x, int y, int z, float volume, float pitch, int count);

    void requestCrossRegionTransfer(int srcX, int srcY, int srcZ, int dstX, int dstY, int dstZ, FluidKind kind, int amount);

    void enqueueCell(int x, int y, int z, FluidKind kind);

    void enqueueNeighborsOnly(int x, int y, int z, FluidKind kind);

    static boolean isFragileBreakable(Material type) {
        return FRAGILE_BREAKABLE.contains(type)
                || Tag.FLOWERS.isTagged(type)
                || Tag.SAPLINGS.isTagged(type);
    }

    static boolean isMarineFlora(Material type) {
        return MARINE_FLORA.contains(type);
    }

    static boolean isSafeWaterlogBlock(BlockData data) {
        return data instanceof Slab || data instanceof Stairs || data instanceof Fence
                || data instanceof Gate || data instanceof TrapDoor || data instanceof Wall
                || Tag.FENCES.isTagged(data.getMaterial()) || data.getMaterial() == Material.IRON_BARS
                || data.getMaterial() == Material.CHAIN || data.getMaterial() == Material.SCAFFOLDING;
    }

    static int readRawVolumeFromBlock(Block block, FluidKind kind) {
        if (block == null) {
            return 0;
        }
        if (block.getType() == kind.material && block.getBlockData() instanceof Levelled lv) {
            int level = lv.getLevel();
            return level >= 8 ? 2 : (8 - level);
        }
        if (kind == FluidKind.WATER && block.getType() != Material.WATER
                && block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()
                && isSafeWaterlogBlock(block.getBlockData())) {
            return 8;
        }
        return 0;
    }
}
