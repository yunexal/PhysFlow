package com.yunexal.physex;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-language message loader supporting built-in locales (en, uk, pl, de, es),
 * custom user YAML files in plugins/PhysEx/lang/, and automatic per-player locale lookup.
 */
public final class MessageManager {

    private static final List<String> BUNDLED_LOCALES = List.of("en", "uk", "pl", "de", "es");
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final PhysExConfig config;
    private final Map<String, YamlConfiguration> bundles = new ConcurrentHashMap<>();

    public MessageManager(JavaPlugin plugin, PhysExConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reload() {
        bundles.clear();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            plugin.getLogger().warning("Could not create lang directory: " + langDir.getAbsolutePath());
        }

        for (String code : BUNDLED_LOCALES) {
            File target = new File(langDir, code + ".yml");
            if (!target.exists()) {
                plugin.saveResource("lang/" + code + ".yml", false);
            }
        }

        File[] files = langDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String code = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
                try (InputStream stream = plugin.getResource("lang/" + code + ".yml")) {
                    if (stream != null) {
                        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                                new InputStreamReader(stream, StandardCharsets.UTF_8));
                        loaded.setDefaults(defaults);
                    }
                } catch (Exception ignored) {
                }
                bundles.put(code, loaded);
            }
        }

        if (!bundles.containsKey("en")) {
            try (InputStream stream = plugin.getResource("lang/en.yml")) {
                if (stream != null) {
                    bundles.put("en", YamlConfiguration.loadConfiguration(
                            new InputStreamReader(stream, StandardCharsets.UTF_8)));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public String raw(CommandSender sender, String key, Map<String, String> placeholders) {
        String langCode = resolveLocaleFor(sender);
        String text = lookup(langCode, key);
        if (text == null) {
            text = lookup(normalizeCode(config.language), key);
        }
        if (text == null) {
            text = lookup("en", key);
        }
        if (text == null) {
            return key;
        }
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return text;
    }

    public String raw(CommandSender sender, String key) {
        return raw(sender, key, null);
    }

    public Component component(CommandSender sender, String key, Map<String, String> placeholders) {
        return LEGACY_AMPERSAND.deserialize(raw(sender, key, placeholders));
    }

    public Component component(CommandSender sender, String key) {
        return LEGACY_AMPERSAND.deserialize(raw(sender, key, null));
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(component(sender, key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component(sender, key, placeholders));
    }

    private String resolveLocaleFor(CommandSender sender) {
        if (config.perPlayerLocale && sender instanceof Player player) {
            String normalized = normalizeCode(player.locale().toString());
            if (bundles.containsKey(normalized)) {
                return normalized;
            }
            int sep = normalized.indexOf('_');
            if (sep > 0) {
                String base = normalized.substring(0, sep);
                if (bundles.containsKey(base)) {
                    return base;
                }
            }
        }
        String fallback = normalizeCode(config.language);
        if (bundles.containsKey(fallback)) {
            return fallback;
        }
        int sep = fallback.indexOf('_');
        if (sep > 0) {
            String base = fallback.substring(0, sep);
            if (bundles.containsKey(base)) {
                return base;
            }
        }
        return "en";
    }

    private String lookup(String code, String key) {
        YamlConfiguration bundle = bundles.get(code);
        if (bundle == null) {
            return null;
        }
        return bundle.getString(key);
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
