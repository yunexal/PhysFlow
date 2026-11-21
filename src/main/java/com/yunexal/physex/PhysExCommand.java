package com.yunexal.physex;

import com.yunexal.physex.FluidSimulationEngine.FluidKind;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Administrative & diagnostic command handler for /physex with multi-language support.
 */
public class PhysExCommand implements CommandExecutor, TabCompleter {

    private static final List<String> CONFIG_KEYS = List.of(
            "general.enabled",
            "general.async-processing",
            "general.async-worker-threads",
            "general.language",
            "general.per-player-locale",
            "general.max-tick-time-ms",
            "general.max-updates-per-tick",
            "general.min-tps-threshold",
            "general.unfreeze-marine-flora-on-drain",
            "water.enabled",
            "water.finite-mode",
            "water.pressure-enabled",
            "water.waterlog-capable-blocks",
            "water.consume-on-bottle-fill",
            "water.consume-on-farmland-hydrate",
            "water.rain-fills-world",
            "water.evaporate-thin-puddles-in-dry-biomes",
            "lava.enabled",
            "lava.finite-mode",
            "lava.pressure-enabled",
            "water-lava-interaction.mode"
    );

    private static final List<String> LANGUAGE_CODES = List.of("en", "uk", "pl", "de", "es");

    private final PhysExPlugin plugin;
    private final PhysExConfig config;
    private final MessageManager messages;
    private final FluidSimulationEngine engine;

    public PhysExCommand(PhysExPlugin plugin, PhysExConfig config, MessageManager messages, FluidSimulationEngine engine) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("physex.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                messages.send(sender, "reload-success");
            }
            case "pause" -> {
                engine.setPaused(true);
                messages.send(sender, "paused");
            }
            case "resume" -> {
                engine.setPaused(false);
                messages.send(sender, "resumed");
            }
            case "clearqueue" -> {
                engine.clearAllQueues();
                messages.send(sender, "queue-cleared");
            }
            case "drain" -> handleDrainOrFill(sender, args, true);
            case "fill" -> handleDrainOrFill(sender, args, false);
            case "set" -> handleSetOption(sender, args);
            default -> messages.send(sender, "usage");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        String tpsStr = String.format(Locale.US, "%.2f", engine.getCurrentTpsSafe());
        String tickMsStr = String.format(Locale.US, "%.2f", engine.getLastTickElapsedMs());
        String asyncMsStr = String.format(Locale.US, "%.2f", engine.getLastAsyncSolveMs());
        String platform = SchedulerBridge.isFolia() ? "Folia (Regionized)" : "Paper/Purpur";
        String pipeline = config.asyncProcessing ? ("ASYNC(" + config.asyncWorkerThreads + "w)") : "SYNC";
        String stateKey = config.generalEnabled
                ? (engine.isPaused() ? "state-paused" : "state-active")
                : "state-disabled";

        messages.send(sender, "status-header", Map.of("platform", platform));
        messages.send(sender, "status-line1", Map.of(
                "state", messages.raw(sender, stateKey),
                "pipeline", pipeline,
                "tps", tpsStr,
                "ms", tickMsStr,
                "asyncMs", asyncMsStr,
                "blocks", String.valueOf(engine.getLastTickUpdatedBlocks())
        ));
        messages.send(sender, "status-line2", Map.of(
                "water", String.valueOf(engine.getPendingWaterCount()),
                "lava", String.valueOf(engine.getPendingLavaCount()),
                "total", String.valueOf(engine.getTotalUpdatesProcessed()),
                "throttled", String.valueOf(engine.getTotalThrottledTicks())
        ));
        messages.send(sender, "status-line3", Map.of(
                "wfinite", String.valueOf(config.waterFiniteMode),
                "wpressure", String.valueOf(config.waterPressureEnabled),
                "lfinite", String.valueOf(config.lavaFiniteMode),
                "lpressure", String.valueOf(config.lavaPressureEnabled),
                "mode", config.lavaWaterMode.name()
        ));
    }

    private void handleDrainOrFill(CommandSender sender, String[] args, boolean drain) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        int radius = 8;
        if (args.length >= 2) {
            try {
                radius = Math.max(1, Math.min(32, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                messages.send(sender, "invalid-radius");
                return;
            }
        }
        FluidKind kind = null;
        if (args.length >= 3) {
            if (args[2].equalsIgnoreCase("water")) {
                kind = FluidKind.WATER;
            } else if (args[2].equalsIgnoreCase("lava")) {
                kind = FluidKind.LAVA;
            }
        } else if (!drain) {
            kind = FluidKind.WATER;
        }

        int finalRadius = radius;
        if (drain) {
            engine.drainRadiusAsync(player.getLocation(), radius, kind, count ->
                    SchedulerBridge.runAtEntity(plugin, player, () ->
                            messages.send(player, "drain-done", Map.of(
                                    "radius", String.valueOf(finalRadius),
                                    "count", String.valueOf(count)
                            ))));
        } else {
            FluidKind finalKind = kind;
            engine.fillRadiusAsync(player.getLocation(), radius, kind, count ->
                    SchedulerBridge.runAtEntity(plugin, player, () -> {
                        String kindLabel = messages.raw(player,
                                finalKind == FluidKind.LAVA ? "fluid-lava" : "fluid-water");
                        messages.send(player, "fill-done", Map.of(
                                "kind", kindLabel,
                                "radius", String.valueOf(finalRadius),
                                "count", String.valueOf(count)
                        ));
                    }));
        }
    }

    private void handleSetOption(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "set-usage");
            return;
        }
        String path = args[1];
        String rawVal = args[2];
        if (!plugin.getConfig().contains(path)) {
            messages.send(sender, "set-unknown-key", Map.of("path", path));
            return;
        }

        Object current = plugin.getConfig().get(path);
        String savedVal = rawVal;
        if (current instanceof Boolean) {
            boolean parsed = Boolean.parseBoolean(rawVal);
            plugin.getConfig().set(path, parsed);
            savedVal = String.valueOf(parsed);
        } else if (current instanceof Integer) {
            try {
                int parsed = Integer.parseInt(rawVal);
                plugin.getConfig().set(path, parsed);
                savedVal = String.valueOf(parsed);
            } catch (NumberFormatException ex) {
                messages.send(sender, "set-invalid-int");
                return;
            }
        } else if (current instanceof Double || current instanceof Float) {
            try {
                double parsed = Double.parseDouble(rawVal);
                plugin.getConfig().set(path, parsed);
                savedVal = String.valueOf(parsed);
            } catch (NumberFormatException ex) {
                messages.send(sender, "set-invalid-number");
                return;
            }
        } else if (path.endsWith(".mode")) {
            savedVal = rawVal.toUpperCase(Locale.ROOT);
            plugin.getConfig().set(path, savedVal);
        } else {
            savedVal = rawVal.toLowerCase(Locale.ROOT);
            plugin.getConfig().set(path, savedVal);
        }

        plugin.saveConfig();
        config.load(plugin.getConfig());
        engine.ensureAsyncPoolIfNeeded();
        messages.send(sender, "set-saved", Map.of("path", path, "value", savedVal));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("physex.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "reload", "pause", "resume", "clearqueue", "drain", "fill", "set").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return CONFIG_KEYS.stream()
                    .filter(k -> k.startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("general.language")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return LANGUAGE_CODES.stream()
                    .filter(l -> l.startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("water-lava-interaction.mode")) {
            String prefix = args[2].toUpperCase(Locale.ROOT);
            return List.of("PHYSEX_EVAPORATE", "CONSUME_WATER_STONE", "VANILLA").stream()
                    .filter(m -> m.startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("drain") || args[0].equalsIgnoreCase("fill"))) {
            return List.of("water", "lava");
        }
        return Collections.emptyList();
    }
}
