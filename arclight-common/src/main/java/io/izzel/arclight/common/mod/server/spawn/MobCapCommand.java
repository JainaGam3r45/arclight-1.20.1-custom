package io.izzel.arclight.common.mod.server.spawn;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.SpawnSpec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.EnumMap;
import java.util.Map;

/**
 * Brigadier + Bukkit command for spawn budget / mobcap inspection.
 * Permission must match VanillaCommandWrapper ({@code minecraft.command.mobcap}).
 */
public final class MobCapCommand {

    private static final String PERMISSION = "minecraft.command.mobcap";

    private MobCapCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Bukkit.getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("mobcap")
            .requires(source -> ((CommandSourceBridge) source).bridge$hasPermission(2, PERMISSION))
            .executes(MobCapCommand::show);
        dispatcher.register(command);

        Command bukkit = ((CraftServer) Bukkit.getServer()).getCommandMap().getCommand("mobcap");
        if (bukkit != null) {
            bukkit.setPermission(PERMISSION);
            bukkit.setPermissionMessage(ChatColor.RED + "You do not have permission for /mobcap.");
        }
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            MinecraftServer server = source.getServer();
            SpawnSpec spawn = ArclightConfig.spec().getOptimization().getSpawn();
            CraftServer craft = (CraftServer) Bukkit.getServer();
            double mspt = EntityGenerationManager.estimateMspt(server);
            int players = server.getPlayerCount();
            String mode = EntityGenerationManager.describeMode(server);

            msg(source, ChatColor.GOLD + "=== Arclight mobcap ===");
            msg(source, ChatColor.YELLOW + "Mode: " + ChatColor.WHITE + mode
                + ChatColor.GRAY + " (players=" + players + ", mspt~" + String.format("%.1f", mspt)
                + " tickavg)");
            msg(source, ChatColor.YELLOW + "optimization.spawn: "
                + ChatColor.WHITE + "enabled=" + spawn.isEnabled()
                + " skip>=" + spawn.getSkipWhenMsptAbove()
                + " maxChunks=" + spawn.getMaxChunksPerTick()
                + " maxMs=" + spawn.getMaxMsPerTick());
            msg(source, ChatColor.YELLOW + "  perPlayer=" + spawn.isPerPlayerMobSpawns()
                + " useMobSpawnRange=" + spawn.isUseMobSpawnRange()
                + " relaxedPlayers<=" + spawn.getRelaxedWhenPlayersAtMost()
                + " relaxedMspt<" + spawn.getRelaxedWhenMsptBelow()
                + " localScale=" + spawn.getLocalMobCapScale());

            StringBuilder localCaps = new StringBuilder(ChatColor.YELLOW + "local mobcap:");
            for (MobCategory category : MobCategory.values()) {
                if (category == MobCategory.MISC) {
                    continue;
                }
                localCaps.append(ChatColor.GRAY).append(" ").append(category.getName())
                    .append(ChatColor.WHITE).append("=").append(EntityGenerationManager.getLocalMobCap(category));
            }
            msg(source, localCaps.toString());

            StringBuilder limits = new StringBuilder(ChatColor.YELLOW + "bukkit spawn-limits (world ceiling):");
            StringBuilder ticks = new StringBuilder(ChatColor.YELLOW + "bukkit ticks-per:");
            for (SpawnCategory category : SpawnCategory.values()) {
                if (!CraftSpawnCategory.isValidForLimits(category)) {
                    continue;
                }
                limits.append(ChatColor.GRAY).append(" ").append(category.name().toLowerCase())
                    .append(ChatColor.WHITE).append("=").append(craft.getSpawnLimit(category));
                ticks.append(ChatColor.GRAY).append(" ").append(category.name().toLowerCase())
                    .append(ChatColor.WHITE).append("=").append(craft.getTicksPerSpawns(category));
            }
            msg(source, limits.toString());
            msg(source, ticks.toString());

            ServerLevel level = source.getEntity() instanceof ServerPlayer player
                ? player.serverLevel()
                : server.overworld();
            if (level != null) {
                Map<MobCategory, Integer> counts = countMobs(level);
                StringBuilder live = new StringBuilder(ChatColor.YELLOW + "live in " + level.dimension().location() + ":");
                for (MobCategory category : MobCategory.values()) {
                    if (category == MobCategory.MISC) {
                        continue;
                    }
                    live.append(ChatColor.GRAY).append(" ").append(category.getName())
                        .append(ChatColor.WHITE).append("=").append(counts.getOrDefault(category, 0));
                }
                msg(source, live.toString());
            }
            msg(source, ChatColor.DARK_GRAY + "Tip: world ceiling = bukkit spawn-limits; local = local-mob-cap-scale / local-mob-cap");
            msg(source, ChatColor.DARK_GRAY + "Use /arclight reload to reload arclight.yml / bukkit.yml / spigot.yml");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(ChatColor.RED + "mobcap failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static void msg(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        try {
            CommandSender bukkit = ((CommandSourceBridge) source).bridge$getBukkitSender();
            if (bukkit != null) {
                bukkit.sendMessage(text);
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<MobCategory, Integer> countMobs(ServerLevel level) {
        EnumMap<MobCategory, Integer> counts = new EnumMap<>(MobCategory.class);
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            MobCategory category = mob.getType().getCategory();
            counts.merge(category, 1, Integer::sum);
        }
        return counts;
    }
}
