package io.izzel.arclight.common.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import io.izzel.arclight.common.bridge.core.entity.MobEntityBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.Collection;
import java.util.List;

/**
 * Force nearby mobs (via entity selector) to aggro a player.
 * Optional {@code path} mode adds temporary chase assist.
 * {@code clear} removes aggro / path assist in emergencies.
 * Permission must match VanillaCommandWrapper ({@code minecraft.command.target}).
 */
public final class TargetCommand {

    private static final String PERMISSION = "minecraft.command.target";
    private static final int DEFAULT_RADIUS = 32;
    private static final int CLEAR_DEFAULT_RADIUS = 512;
    private static final int MAX_RADIUS = 512;

    private TargetCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Bukkit.getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }

        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("target")
            .requires(source -> ((CommandSourceBridge) source).bridge$hasPermission(2, PERMISSION));

        command.then(playerEntitiesBranch(false));
        command.then(Commands.literal("path").then(playerEntitiesBranch(true)));
        command.then(Commands.literal("clear")
            .then(Commands.literal("path").executes(TargetCommand::clearPathAll))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(ctx -> clearPlayer(ctx, CLEAR_DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                    .executes(ctx -> clearPlayer(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))));

        dispatcher.register(command);

        Command bukkit = ((CraftServer) Bukkit.getServer()).getCommandMap().getCommand("target");
        if (bukkit != null) {
            bukkit.setPermission(PERMISSION);
            bukkit.setPermissionMessage(ChatColor.RED + "You do not have permission for /target.");
        }
    }

    private static ArgumentBuilder<CommandSourceStack, ?> playerEntitiesBranch(boolean path) {
        return Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("entities", EntityArgument.entities())
                .executes(ctx -> run(ctx, DEFAULT_RADIUS, path))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                    .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "radius"), path))));
    }

    private static int run(CommandContext<CommandSourceStack> context, int radius, boolean path)
        throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer victim = EntityArgument.getPlayer(context, "player");
        Collection<? extends Entity> entities = EntityArgument.getEntities(context, "entities");
        double radiusSq = (double) radius * (double) radius;

        int targeted = 0;
        int skipped = 0;
        int pathEnrolled = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                skipped++;
                continue;
            }
            if (!canForceAggro(mob)) {
                skipped++;
                continue;
            }
            if (mob.level() != victim.level()) {
                skipped++;
                continue;
            }
            if (mob.distanceToSqr(victim) > radiusSq) {
                skipped++;
                continue;
            }

            boolean already = mob.getTarget() == victim;
            if (!already) {
                boolean ok = ((MobEntityBridge) mob).bridge$setGoalTarget(victim, EntityTargetEvent.TargetReason.CUSTOM, true);
                if (!ok) {
                    skipped++;
                    continue;
                }
            }
            targeted++;

            if (path && TargetPathAssist.enroll(mob, victim, radius)) {
                pathEnrolled++;
            }
        }

        if (targeted == 0) {
            msg(source, ChatColor.YELLOW + "No mobs targeted "
                + ChatColor.WHITE + victim.getGameProfile().getName()
                + ChatColor.YELLOW + " within " + radius + " blocks"
                + ChatColor.GRAY + " (selector=" + entities.size() + ", skipped=" + skipped + ").");
            return 0;
        }

        StringBuilder line = new StringBuilder()
            .append(ChatColor.GREEN).append("Set ").append(ChatColor.WHITE).append(targeted)
            .append(ChatColor.GREEN).append(" mob(s) to attack ")
            .append(ChatColor.WHITE).append(victim.getGameProfile().getName())
            .append(ChatColor.GRAY).append(" (radius=").append(radius);
        if (skipped > 0) {
            line.append(", skipped=").append(skipped);
        }
        if (path) {
            line.append(", path assist: ").append(pathEnrolled).append(" active");
        }
        line.append(").");
        msg(source, line.toString());
        return targeted;
    }

    private static int clearPathAll(CommandContext<CommandSourceStack> context) {
        int cleared = TargetPathAssist.clearAll();
        msg(context.getSource(), ChatColor.GREEN + "Cleared " + ChatColor.WHITE + cleared
            + ChatColor.GREEN + " path assist session(s).");
        return cleared;
    }

    private static int clearPlayer(CommandContext<CommandSourceStack> context, int radius)
        throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer victim = EntityArgument.getPlayer(context, "player");
        ServerLevel level = victim.serverLevel();
        double radiusSq = (double) radius * (double) radius;
        AABB box = victim.getBoundingBox().inflate(radius);

        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box, mob ->
            mob.isAlive() && mob.getTarget() == victim && mob.distanceToSqr(victim) <= radiusSq);

        int clearedTargets = 0;
        int pathSessions = 0;
        for (Mob mob : mobs) {
            if (TargetPathAssist.clearMob(mob)) {
                pathSessions++;
            }
            boolean ok = ((MobEntityBridge) mob).bridge$setGoalTarget(null, EntityTargetEvent.TargetReason.FORGOT_TARGET, true);
            mob.getNavigation().stop();
            if (ok || mob.getTarget() == null) {
                clearedTargets++;
            }
        }
        // Also drop path sessions for this victim that may be outside the AABB scan edge cases
        pathSessions += TargetPathAssist.clearFor(victim);

        msg(source, ChatColor.GREEN + "Cleared " + ChatColor.WHITE + clearedTargets
            + ChatColor.GREEN + " mob target(s) on "
            + ChatColor.WHITE + victim.getGameProfile().getName()
            + ChatColor.GRAY + " (radius=" + radius + ", path sessions: " + pathSessions + ").");
        return clearedTargets;
    }

    /**
     * Hostile/attacking mobs only — skips passive animals and other non-{@link Enemy} mobs.
     */
    private static boolean canForceAggro(Mob mob) {
        return mob instanceof Enemy;
    }

    private static void msg(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), true);
        try {
            CommandSender bukkit = ((CommandSourceBridge) source).bridge$getBukkitSender();
            if (bukkit != null) {
                bukkit.sendMessage(text);
            }
        } catch (Exception ignored) {
        }
    }
}
