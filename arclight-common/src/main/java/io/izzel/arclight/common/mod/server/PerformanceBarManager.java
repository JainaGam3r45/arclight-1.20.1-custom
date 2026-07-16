package io.izzel.arclight.common.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.bridge.core.entity.player.ServerPlayerEntityBridge;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PerformanceBarManager {

    private static final Map<UUID, EnumMap<BarType, BossBar>> BARS = new HashMap<>();
    private static final Map<UUID, EnumSet<BarType>> ENABLED = new HashMap<>();
    private static BarSettings tpsSettings = BarSettings.tpsDefaults();
    private static BarSettings ramSettings = BarSettings.ramDefaults();
    private static long ticks;

    private PerformanceBarManager() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        reloadConfiguration();
        registerPermission("bukkit.command.tpsbar");
        registerPermission("bukkit.command.tpsbar.other");
        registerPermission("bukkit.command.rambar");
        registerPermission("bukkit.command.rambar.other");
        register(dispatcher, BarType.TPS, "bukkit.command.tpsbar");
        register(dispatcher, BarType.RAM, "bukkit.command.rambar");
    }

    private static void registerPermission(String name) {
        if (Bukkit.getPluginManager().getPermission(name) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(name, PermissionDefault.OP));
        }
    }

    public static void reloadConfiguration() {
        migrateLegacyConfiguration();
        YamlConfiguration config = ArclightConfiguration.reload();
        tpsSettings = BarSettings.load(config, "performance-bars.tpsbar", BarSettings.tpsDefaults());
        ramSettings = BarSettings.load(config, "performance-bars.rambar", BarSettings.ramDefaults());
        loadEnabled(config);
        try {
            ArclightConfiguration.save();
        } catch (IllegalStateException exception) {
            Bukkit.getLogger().warning("Unable to save Arclight performance bar settings: " + exception.getMessage());
        }
        updateAll(ArclightServer.getMinecraftServer());
    }

    private static void migrateLegacyConfiguration() {
        File legacyFile = new File("bukkit.yml");
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
        if (!ArclightConfiguration.copySection(legacy, "arclight-bars", "performance-bars")) {
            return;
        }
        legacy.set("arclight-bars", null);
        try {
            legacy.save(legacyFile);
            ArclightConfiguration.save();
        } catch (IOException exception) {
            Bukkit.getLogger().warning("Unable to migrate Arclight performance bar settings: " + exception.getMessage());
        }
    }

    private static void loadEnabled(YamlConfiguration config) {
        ENABLED.clear();
        ConfigurationSection players = config.getConfigurationSection("performance-bars.player-state");
        if (players == null) {
            return;
        }
        for (String playerId : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(playerId);
                EnumSet<BarType> types = EnumSet.noneOf(BarType.class);
                for (BarType type : BarType.values()) {
                    if (players.getBoolean(playerId + "." + type.command, false)) {
                        types.add(type);
                    }
                }
                if (!types.isEmpty()) {
                    ENABLED.put(uuid, types);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (BARS.isEmpty() && ENABLED.isEmpty()) {
            return;
        }
        ticks++;
        if (ticks % tpsSettings.tickInterval == 0 || ticks % ramSettings.tickInterval == 0) {
            restoreOnlinePlayers(server);
            updateAll(server);
        }
    }

    public static void restore(ServerPlayer player) {
        if (ENABLED.containsKey(player.getUUID())) {
            restore(player, player.server);
        }
    }

    private static void restoreOnlinePlayers(MinecraftServer server) {
        for (UUID uuid : ENABLED.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                restore(player, server);
            }
        }
    }

    private static void restore(ServerPlayer player, MinecraftServer server) {
        EnumSet<BarType> types = ENABLED.get(player.getUUID());
        if (types == null) {
            return;
        }
        for (BarType type : types) {
            show(player, type, server);
        }
    }

    public static void remove(UUID playerId) {
        EnumMap<BarType, BossBar> bars = BARS.remove(playerId);
        if (bars != null) {
            bars.values().forEach(BossBar::removeAll);
        }
    }

    public static void clear() {
        BARS.values().forEach(bars -> bars.values().forEach(BossBar::removeAll));
        BARS.clear();
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, BarType type, String permission) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(type.command)
            .requires(source -> ((CommandSourceBridge) source).bridge$getBukkitSender().hasPermission(permission))
            .executes(context -> toggleSelf(context, type));
        command.then(Commands.argument("targets", EntityArgument.players())
            .requires(source -> ((CommandSourceBridge) source).bridge$getBukkitSender().hasPermission(permission + ".other"))
            .executes(context -> toggleTargets(context, type)));
        dispatcher.register(command);
    }

    private static int toggleSelf(CommandContext<CommandSourceStack> context, BarType type) {
        try {
            toggle(context.getSource(), context.getSource().getPlayerOrException(), type);
            return 1;
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal("A player target is required from the console."));
            return 0;
        }
    }

    private static int toggleTargets(CommandContext<CommandSourceStack> context, BarType type) {
        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "targets");
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal("No matching players were found."));
            return 0;
        }
        for (ServerPlayer target : targets) {
            toggle(context.getSource(), target, type);
        }
        return targets.size();
    }

    private static void toggle(CommandSourceStack source, ServerPlayer target, BarType type) {
        Player player = ((ServerPlayerEntityBridge) target).bridge$getBukkitEntity();
        EnumMap<BarType, BossBar> playerBars = BARS.computeIfAbsent(player.getUniqueId(), key -> new EnumMap<>(BarType.class));
        BossBar current = playerBars.remove(type);
        if (current != null) {
            current.removeAll();
            setEnabled(player.getUniqueId(), type, false);
            if (playerBars.isEmpty()) {
                BARS.remove(player.getUniqueId());
            }
            source.sendSuccess(() -> Component.literal(type.displayName + " bar disabled for " + player.getName()), false);
            return;
        }
        setEnabled(player.getUniqueId(), type, true);
        show(target, type, source.getServer());
        source.sendSuccess(() -> Component.literal(type.displayName + " bar enabled for " + player.getName()), false);
    }

    private static void show(ServerPlayer target, BarType type, MinecraftServer server) {
        Player player = ((ServerPlayerEntityBridge) target).bridge$getBukkitEntity();
        EnumMap<BarType, BossBar> bars = BARS.computeIfAbsent(player.getUniqueId(), key -> new EnumMap<>(BarType.class));
        if (bars.containsKey(type)) {
            return;
        }
        BarSettings settings = settings(type);
        BossBar bar = Bukkit.createBossBar(settings.title, settings.good, settings.style);
        bar.addPlayer(player);
        bars.put(type, bar);
        update(player, type, bar, server);
    }

    private static void setEnabled(UUID playerId, BarType type, boolean enabled) {
        EnumSet<BarType> types = ENABLED.computeIfAbsent(playerId, key -> EnumSet.noneOf(BarType.class));
        if (enabled) {
            types.add(type);
        } else {
            types.remove(type);
            if (types.isEmpty()) {
                ENABLED.remove(playerId);
            }
        }
        YamlConfiguration config = ArclightConfiguration.get();
        String path = "performance-bars.player-state." + playerId + "." + type.command;
        config.set(path, enabled ? true : null);
        if (!enabled && !config.contains("performance-bars.player-state." + playerId)) {
            config.set("performance-bars.player-state." + playerId, null);
        }
        try {
            ArclightConfiguration.save();
        } catch (IllegalStateException exception) {
            Bukkit.getLogger().warning("Unable to save Arclight performance bar state: " + exception.getMessage());
        }
    }

    private static void updateAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        BARS.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().values().forEach(BossBar::removeAll);
                return true;
            }
            entry.getValue().forEach((type, bar) -> update(player, type, bar, server));
            return false;
        });
    }

    private static void update(Player player, BarType type, BossBar bar, MinecraftServer server) {
        double progress;
        String title;
        if (type == BarType.TPS) {
            double tps = Math.min(20.0, ((MinecraftServerBridge) server).bridge$getRecentTps());
            double mspt = server.getAverageTickTime();
            int ping = player.getPing();
            boolean good;
            boolean medium;
            BarColor color;
            switch (tpsSettings.fillMode) {
                case TPS -> {
                    progress = tps / 20.0;
                    good = tps >= 18.0;
                    medium = tps >= 15.0;
                }
                case PING -> {
                    progress = 1.0 - Math.min(ping, 300) / 300.0;
                    good = ping <= 100;
                    medium = ping <= 200;
                }
                case MSPT -> {
                    progress = 1.0 - Math.min(mspt, 50.0) / 50.0;
                    good = mspt <= 40.0;
                    medium = mspt <= 50.0;
                }
                default -> throw new IllegalStateException("Unexpected TPS bar fill mode");
            }
            boolean msptGood = mspt <= 40.0;
            boolean msptMedium = mspt <= 50.0;
            boolean pingGood = ping <= 100;
            boolean pingMedium = ping <= 200;
            color = tpsSettings.colorFor(good && msptGood && pingGood, medium && msptMedium && pingMedium);
            title = formatTpsTitle(tpsSettings, tps, mspt, ping, good, medium, msptGood, msptMedium, pingGood, pingMedium);
            bar.setColor(color);
        } else {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long maximum = runtime.maxMemory();
            progress = maximum == 0 ? 0.0 : 1.0 - (double) used / maximum;
            boolean good = progress >= 0.5;
            boolean medium = progress >= 0.25;
            title = formatRamTitle(ramSettings, used, maximum, (1.0 - progress) * 100.0, good, medium);
            bar.setColor(ramSettings.colorFor(good, medium));
        }
        double clamped = clamp(progress);
        bar.setTitle(title);
        bar.setProgress(clamped);
    }

    private static BarSettings settings(BarType type) {
        return type == BarType.TPS ? tpsSettings : ramSettings;
    }

    private static String formatTpsTitle(BarSettings settings, double tps, double mspt, int ping, boolean tpsGood, boolean tpsMedium, boolean msptGood, boolean msptMedium, boolean pingGood, boolean pingMedium) {
        return ChatColor.translateAlternateColorCodes('&', settings.title
            .replace("<tps>", settings.metric(String.format(Locale.ROOT, "%.2f", tps), tpsGood, tpsMedium))
            .replace("<mspt>", settings.metric(String.format(Locale.ROOT, "%.2f", mspt), msptGood, msptMedium))
            .replace("<ping>", settings.metric(Integer.toString(ping), pingGood, pingMedium)));
    }

    private static String formatRamTitle(BarSettings settings, long used, long maximum, double percent, boolean good, boolean medium) {
        return ChatColor.translateAlternateColorCodes('&', settings.title
            .replace("<used>", settings.metric(formatMemory(used), good, medium))
            .replace("<xmx>", formatMemory(maximum))
            .replace("<percent>", settings.metric(String.format(Locale.ROOT, "%.1f%%", percent), good, medium)));
    }

    private static String formatMemory(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private enum BarType {
        TPS("tpsbar", "TPS"),
        RAM("rambar", "RAM");

        private final String command;
        private final String displayName;

        BarType(String command, String displayName) {
            this.command = command;
            this.displayName = displayName;
        }
    }

    private enum FillMode {
        TPS,
        MSPT,
        PING
    }

    private record BarSettings(String title, BarStyle style, FillMode fillMode, BarColor good, BarColor medium, BarColor low, String goodText, String mediumText, String lowText, int tickInterval) {

        private static BarSettings tpsDefaults() {
            return new BarSettings("&7TPS: <tps> &7MSPT: <mspt> &7Ping: <ping>ms", BarStyle.SEGMENTED_20, FillMode.MSPT, BarColor.GREEN, BarColor.YELLOW, BarColor.RED, "&a<text>", "&e<text>", "&c<text>", 20);
        }

        private static BarSettings ramDefaults() {
            return new BarSettings("&7RAM: <used>&7/<xmx> &7(<percent>)", BarStyle.SEGMENTED_20, FillMode.MSPT, BarColor.GREEN, BarColor.YELLOW, BarColor.RED, "&a<text>", "&e<text>", "&c<text>", 20);
        }

        private static BarSettings load(YamlConfiguration config, String path, BarSettings defaults) {
            setDefault(config, path + ".title", defaults.title);
            String title = config.getString(path + ".title", defaults.title);
            if (path.endsWith("tpsbar") && (title.equals("&7TPS&6: &f<tps> &7MSPT&6: &f<mspt> &7Ping&6: &f<ping>ms") || title.equals("TPS: <tps> MSPT: <mspt> Ping: <ping>ms"))) {
                title = defaults.title;
                config.set(path + ".title", title);
            } else if (path.endsWith("rambar") && (title.equals("&7RAM&6: &f<used>/<xmx> &7(<percent>)") || title.equals("RAM: <used>/<xmx> (<percent>)"))) {
                title = defaults.title;
                config.set(path + ".title", title);
            }
            setDefault(config, path + ".overlay", purpurOverlay(defaults.style));
            setDefault(config, path + ".fill-mode", defaults.fillMode.name());
            setDefault(config, path + ".progress-color.good", defaults.good.name());
            setDefault(config, path + ".progress-color.medium", defaults.medium.name());
            setDefault(config, path + ".progress-color.low", defaults.low.name());
            setDefault(config, path + ".text-color.good", defaults.goodText);
            setDefault(config, path + ".text-color.medium", defaults.mediumText);
            setDefault(config, path + ".text-color.low", defaults.lowText);
            setDefault(config, path + ".tick-interval", defaults.tickInterval);
            return new BarSettings(
                title,
                overlay(config.getString(path + ".overlay"), defaults.style),
                valueOf(FillMode.class, config.getString(path + ".fill-mode"), defaults.fillMode),
                valueOf(BarColor.class, config.getString(path + ".progress-color.good"), defaults.good),
                valueOf(BarColor.class, config.getString(path + ".progress-color.medium"), defaults.medium),
                valueOf(BarColor.class, config.getString(path + ".progress-color.low"), defaults.low),
                config.getString(path + ".text-color.good", defaults.goodText),
                config.getString(path + ".text-color.medium", defaults.mediumText),
                config.getString(path + ".text-color.low", defaults.lowText),
                Math.max(1, config.getInt(path + ".tick-interval", defaults.tickInterval))
            );
        }

        private BarColor colorFor(boolean isGood, boolean isMedium) {
            return isGood ? good : isMedium ? medium : low;
        }

        private String metric(String text, boolean isGood, boolean isMedium) {
            String format = isGood ? goodText : isMedium ? mediumText : lowText;
            return ChatColor.translateAlternateColorCodes('&', format.replace("<text>", text));
        }

        private static void setDefault(YamlConfiguration config, String path, Object value) {
            if (!config.contains(path)) {
                config.set(path, value);
            }
        }

        private static <T extends Enum<T>> T valueOf(Class<T> type, String value, T fallback) {
            try {
                return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                return fallback;
            }
        }

        private static BarStyle overlay(String value, BarStyle fallback) {
            if (value != null && value.startsWith("NOTCHED_")) {
                value = "SEGMENTED_" + value.substring("NOTCHED_".length());
            }
            return valueOf(BarStyle.class, value, fallback);
        }

        private static String purpurOverlay(BarStyle style) {
            return style.name().startsWith("SEGMENTED_") ? "NOTCHED_" + style.name().substring("SEGMENTED_".length()) : style.name();
        }
    }
}
