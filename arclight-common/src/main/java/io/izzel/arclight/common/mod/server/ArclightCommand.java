package io.izzel.arclight.common.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.bridge.bukkit.CraftServerBridge;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * General Arclight admin command. Permission must match VanillaCommandWrapper
 * ({@code minecraft.command.arclight}).
 */
public final class ArclightCommand {

    private static final String PERMISSION = "minecraft.command.arclight";

    private ArclightCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Bukkit.getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("arclight")
            .requires(source -> ((CommandSourceBridge) source).bridge$hasPermission(2, PERMISSION));

        LiteralArgumentBuilder<CommandSourceStack> reload = Commands.literal("reload")
            .requires(source -> ((CommandSourceBridge) source).bridge$hasPermission(2, PERMISSION))
            .executes(ctx -> reload(ctx, true, true, true));

        reload.then(Commands.literal("all").executes(ctx -> reload(ctx, true, true, true)));
        reload.then(Commands.literal("arclight").executes(ctx -> reload(ctx, true, false, false)));
        reload.then(Commands.literal("bukkit").executes(ctx -> reload(ctx, false, true, false)));
        reload.then(Commands.literal("spigot").executes(ctx -> reload(ctx, false, false, true)));

        root.then(reload);
        root.executes(ArclightCommand::help);
        dispatcher.register(root);

        Command bukkit = ((CraftServer) Bukkit.getServer()).getCommandMap().getCommand("arclight");
        if (bukkit != null) {
            bukkit.setPermission(PERMISSION);
            bukkit.setPermissionMessage(ChatColor.RED + "You do not have permission for /arclight.");
        }
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        msg(context.getSource(), ChatColor.GOLD + "=== Arclight ===");
        msg(context.getSource(), ChatColor.YELLOW + "/arclight reload"
            + ChatColor.GRAY + " — reload arclight.yml + bukkit.yml + spigot.yml (no plugin unload)");
        msg(context.getSource(), ChatColor.YELLOW + "/arclight reload [all|arclight|bukkit|spigot]");
        msg(context.getSource(), ChatColor.DARK_GRAY + "Some boot-only options still need a full restart.");
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context, boolean arclight, boolean bukkit, boolean spigot) {
        CommandSourceStack source = context.getSource();
        StringBuilder done = new StringBuilder();
        try {
            if (arclight) {
                ArclightConfiguration.refreshFromDisk();
                PerformanceBarManager.reloadConfiguration();
                DropManager.reloadConfiguration();
                append(done, "arclight.yml");
            }
            CraftServerBridge bridge = (CraftServerBridge) Bukkit.getServer();
            if (bukkit) {
                bridge.bridge$reloadBukkitConfig();
                append(done, "bukkit.yml");
            }
            if (spigot) {
                boolean bungeeChanged = bridge.bridge$reloadSpigotConfig();
                append(done, "spigot.yml");
                if (bungeeChanged) {
                    msg(source, ChatColor.RED + "Warning: settings.bungeecord changed. Restart the server for proxy mode to apply safely.");
                }
            }
            msg(source, ChatColor.GREEN + "Reloaded: " + done);
            msg(source, ChatColor.DARK_GRAY + "Note: options captured only at boot (e.g. DFU, some activation flags) may need a restart.");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(ChatColor.RED + "Arclight reload failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static void append(StringBuilder builder, String name) {
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(name);
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
}
