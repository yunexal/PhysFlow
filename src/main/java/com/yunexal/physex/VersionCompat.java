package com.yunexal.physex;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Cross-version compatibility helpers for Minecraft 1.16.5 through 26.3+.
 */
final class VersionCompat {

    private static final Method WORLD_MIN_HEIGHT = findMethod(World.class, "getMinHeight");
    private static final Method PLAYER_LOCALE = findMethod(Player.class, "locale");

    static final Material WATER_CAULDRON = Material.matchMaterial("WATER_CAULDRON");
    static final Material LAVA_CAULDRON = Material.matchMaterial("LAVA_CAULDRON");

    private VersionCompat() {
    }

    private static Method findMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    static int getMinHeight(World world) {
        if (WORLD_MIN_HEIGHT != null) {
            try {
                return (int) WORLD_MIN_HEIGHT.invoke(world);
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("deprecation")
    static String getPlayerLocale(Player player) {
        if (PLAYER_LOCALE != null) {
            try {
                Object loc = PLAYER_LOCALE.invoke(player);
                if (loc != null) {
                    return loc.toString();
                }
            } catch (Exception ignored) {
                // fallback to legacy getLocale()
            }
        }
        return player.getLocale();
    }

    static Set<Material> materialSet(String... names) {
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                set.add(mat);
            }
        }
        return Collections.unmodifiableSet(set);
    }
}
