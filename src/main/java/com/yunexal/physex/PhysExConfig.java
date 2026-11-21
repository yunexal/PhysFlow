package com.yunexal.physex;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Strongly-typed configuration holder for PhysEx with live reload & CLI mutation support.
 */
public class PhysExConfig {

    public enum LavaWaterMode {
        PHYSEX_EVAPORATE,
        CONSUME_WATER_STONE,
        VANILLA
    }

    public boolean generalEnabled = true;
    public boolean asyncProcessing = true;
    public int asyncWorkerThreads = 2;
    public String language = "en";
    public boolean perPlayerLocale = true;
    public final Set<String> worldsWhitelist = new HashSet<>();
    public final Set<String> worldsBlacklist = new HashSet<>();
    public double maxTickTimeMs = 4.5;
    public int maxUpdatesPerTick = 900;
    public int maxQueueSize = 160_000;
    public double minTpsThreshold = 17.5;
    public boolean persistQueueOnShutdown = true;
    public boolean unfreezeMarineFloraOnDrain = true;

    public boolean waterEnabled = true;
    public int waterTickInterval = 2;
    public boolean waterFiniteMode = true;
    public boolean waterGravityEnabled = true;
    public boolean waterEqualizeEnabled = true;
    public int waterMinSpreadDiff = 2;
    public boolean waterPressureEnabled = true;
    public int waterPressureMaxDepth = 20;
    public int waterPressureMaxVisited = 140;
    public boolean waterDisplaceOnPlace = true;
    public boolean waterDisplaceOnPiston = true;
    public boolean waterlogCapableBlocks = true;
    public boolean waterBreakFragileBlocks = true;
    public boolean consumeOnBottleFill = true;
    public int bottleVolumeCost = 3;
    public boolean waterBucketScoopPartial = true;
    public boolean consumeOnFarmlandHydrate = true;
    public double farmlandConsumeChance = 0.18;
    public boolean waterDisableDripstone = true;
    public boolean evaporateThinPuddlesInDryBiomes = true;
    public double thinPuddleEvaporateChance = 0.08;
    public boolean rainFillsWorld = false;
    public int rainFillAttemptsPerTick = 4;

    public boolean lavaEnabled = true;
    public int lavaTickInterval = 8;
    public int lavaNetherTickInterval = 4;
    public boolean lavaFiniteMode = true;
    public boolean lavaGravityEnabled = true;
    public boolean lavaEqualizeEnabled = true;
    public int lavaMinSpreadDiff = 2;
    public boolean lavaPressureEnabled = true;
    public int lavaPressureMaxDepth = 12;
    public int lavaPressureMaxVisited = 80;
    public boolean lavaDisplaceOnPlace = true;
    public boolean lavaDisplaceOnPiston = true;
    public boolean lavaBreakFragileBlocks = true;
    public boolean lavaDisableDripstone = true;
    public boolean lavaBucketScoopPartial = true;

    public LavaWaterMode lavaWaterMode = LavaWaterMode.PHYSEX_EVAPORATE;
    public boolean evaporateEffects = true;

    public boolean meltingIceCreatesFiniteWater = true;

    public void load(FileConfiguration cfg) {
        generalEnabled = cfg.getBoolean("general.enabled", true);
        asyncProcessing = cfg.getBoolean("general.async-processing", true);
        asyncWorkerThreads = Math.max(1, Math.min(16, cfg.getInt("general.async-worker-threads", 2)));
        language = cfg.getString("general.language", "en").trim().toLowerCase(Locale.ROOT);
        perPlayerLocale = cfg.getBoolean("general.per-player-locale", true);
        worldsWhitelist.clear();
        for (String w : cfg.getStringList("general.worlds-whitelist")) {
            worldsWhitelist.add(w.toLowerCase(Locale.ROOT));
        }
        worldsBlacklist.clear();
        for (String w : cfg.getStringList("general.worlds-blacklist")) {
            worldsBlacklist.add(w.toLowerCase(Locale.ROOT));
        }
        maxTickTimeMs = Math.max(0.5, Math.min(25.0, cfg.getDouble("general.max-tick-time-ms", 4.5)));
        maxUpdatesPerTick = Math.max(20, Math.min(20_000, cfg.getInt("general.max-updates-per-tick", 900)));
        maxQueueSize = Math.max(1000, Math.min(2_000_000, cfg.getInt("general.max-queue-size", 160_000)));
        minTpsThreshold = Math.max(5.0, Math.min(19.9, cfg.getDouble("general.min-tps-threshold", 17.5)));
        persistQueueOnShutdown = cfg.getBoolean("general.persist-queue-on-shutdown", true);
        unfreezeMarineFloraOnDrain = cfg.getBoolean("general.unfreeze-marine-flora-on-drain", true);

        waterEnabled = cfg.getBoolean("water.enabled", true);
        waterTickInterval = Math.max(1, cfg.getInt("water.tick-interval", 2));
        waterFiniteMode = cfg.getBoolean("water.finite-mode", true);
        waterGravityEnabled = cfg.getBoolean("water.gravity-enabled", true);
        waterEqualizeEnabled = cfg.getBoolean("water.equalize-enabled", true);
        waterMinSpreadDiff = Math.max(1, Math.min(7, cfg.getInt("water.min-spread-difference", 2)));
        waterPressureEnabled = cfg.getBoolean("water.pressure-enabled", true);
        waterPressureMaxDepth = Math.max(2, Math.min(64, cfg.getInt("water.pressure-max-depth", 20)));
        waterPressureMaxVisited = Math.max(16, Math.min(1024, cfg.getInt("water.pressure-max-visited", 140)));
        waterDisplaceOnPlace = cfg.getBoolean("water.displace-on-block-place", true);
        waterDisplaceOnPiston = cfg.getBoolean("water.displace-on-piston", true);
        waterlogCapableBlocks = cfg.getBoolean("water.waterlog-capable-blocks", true);
        waterBreakFragileBlocks = cfg.getBoolean("water.break-fragile-blocks", true);
        consumeOnBottleFill = cfg.getBoolean("water.consume-on-bottle-fill", true);
        bottleVolumeCost = Math.max(1, Math.min(8, cfg.getInt("water.bottle-volume-cost", 3)));
        waterBucketScoopPartial = cfg.getBoolean("water.bucket-scoop-partial-volume", true);
        consumeOnFarmlandHydrate = cfg.getBoolean("water.consume-on-farmland-hydrate", true);
        farmlandConsumeChance = Math.max(0.0, Math.min(1.0, cfg.getDouble("water.farmland-consume-chance", 0.18)));
        waterDisableDripstone = cfg.getBoolean("water.disable-dripstone-cauldron-fill", true);
        evaporateThinPuddlesInDryBiomes = cfg.getBoolean("water.evaporate-thin-puddles-in-dry-biomes", true);
        thinPuddleEvaporateChance = Math.max(0.0, Math.min(1.0, cfg.getDouble("water.thin-puddle-evaporate-chance", 0.08)));
        rainFillsWorld = cfg.getBoolean("water.rain-fills-world", false);
        rainFillAttemptsPerTick = Math.max(1, Math.min(64, cfg.getInt("water.rain-fill-attempts-per-tick", 4)));

        lavaEnabled = cfg.getBoolean("lava.enabled", true);
        lavaTickInterval = Math.max(1, cfg.getInt("lava.tick-interval", 8));
        lavaNetherTickInterval = Math.max(1, cfg.getInt("lava.nether-tick-interval", 4));
        lavaFiniteMode = cfg.getBoolean("lava.finite-mode", true);
        lavaGravityEnabled = cfg.getBoolean("lava.gravity-enabled", true);
        lavaEqualizeEnabled = cfg.getBoolean("lava.equalize-enabled", true);
        lavaMinSpreadDiff = Math.max(1, Math.min(7, cfg.getInt("lava.min-spread-difference", 2)));
        lavaPressureEnabled = cfg.getBoolean("lava.pressure-enabled", true);
        lavaPressureMaxDepth = Math.max(2, Math.min(64, cfg.getInt("lava.pressure-max-depth", 12)));
        lavaPressureMaxVisited = Math.max(16, Math.min(1024, cfg.getInt("lava.pressure-max-visited", 80)));
        lavaDisplaceOnPlace = cfg.getBoolean("lava.displace-on-block-place", true);
        lavaDisplaceOnPiston = cfg.getBoolean("lava.displace-on-piston", true);
        lavaBreakFragileBlocks = cfg.getBoolean("lava.break-and-burn-fragile-blocks", true);
        lavaDisableDripstone = cfg.getBoolean("lava.disable-dripstone-cauldron-fill", true);
        lavaBucketScoopPartial = cfg.getBoolean("lava.bucket-scoop-partial-volume", true);

        String modeStr = cfg.getString("water-lava-interaction.mode", "PHYSEX_EVAPORATE").toUpperCase(Locale.ROOT);
        try {
            lavaWaterMode = LavaWaterMode.valueOf(modeStr);
        } catch (IllegalArgumentException ignored) {
            lavaWaterMode = LavaWaterMode.PHYSEX_EVAPORATE;
        }
        evaporateEffects = cfg.getBoolean("water-lava-interaction.evaporate-effects", true);
        meltingIceCreatesFiniteWater = cfg.getBoolean("ice-and-sponge.melting-ice-creates-finite-water", true);
    }

    public boolean isWorldEnabled(World world) {
        if (!generalEnabled || world == null) {
            return false;
        }
        String name = world.getName().toLowerCase(Locale.ROOT);
        if (worldsBlacklist.contains(name)) {
            return false;
        }
        return worldsWhitelist.isEmpty() || worldsWhitelist.contains(name);
    }
}
