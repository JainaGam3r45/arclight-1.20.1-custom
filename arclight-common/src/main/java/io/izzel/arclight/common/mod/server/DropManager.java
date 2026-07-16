package io.izzel.arclight.common.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import io.izzel.arclight.common.bridge.core.entity.player.ServerPlayerEntityBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class DropManager {

    private static final String PERMISSION = "bukkit.command.drop";
    private static final int PLATFORM_RADIUS = 2;
    private static final int CHAT_CENTER = 154;
    private static final int NETHER_CEILING_CAP = 120;
    private static final int NETHER_VERTICAL_RANGE = 32;
    private static final int END_VERTICAL_RANGE = 40;
    private static DropState drop;
    private static DropSettings settings = DropSettings.defaults();
    private static boolean loaded;
    private static long ticks;

    private DropManager() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Bukkit.getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }
        reloadConfiguration();
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("drop")
            .requires(source -> ((CommandSourceBridge) source).bridge$getBukkitSender().hasPermission(PERMISSION));
        command.then(Commands.literal("locate").executes(DropManager::locate));
        command.then(Commands.literal("start").executes(DropManager::start));
        command.then(Commands.literal("cancel").executes(DropManager::cancelCommand));
        command.then(Commands.literal("recalculate").executes(DropManager::recalculate));
        dispatcher.register(command);
    }

    public static void reloadConfiguration() {
        YamlConfiguration config = ArclightConfiguration.reload();
        settings = DropSettings.load(config);
        if (!loaded) {
            drop = DropState.load(config);
            loaded = true;
        }
        ArclightConfiguration.save();
    }

    private static int locate(CommandContext<CommandSourceStack> context) {
        if (drop != null) {
            context.getSource().sendFailure(Component.literal("A drop is already pending or active."));
            return 0;
        }
        ServerPlayer source;
        try {
            source = context.getSource().getPlayerOrException();
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal("/drop locate must be run by a player."));
            return 0;
        }
        Player administrator = ((ServerPlayerEntityBridge) source).bridge$getBukkitEntity();
        if (administrator.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            context.getSource().sendFailure(Component.literal("Spectators cannot prepare a drop."));
            return 0;
        }
        World world = administrator.getWorld();
        List<Player> participants = competitors(world);
        if (participants.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Need at least one non-admin player in this world."));
            return 0;
        }
        Location target = findSafeLocation(world, participants);
        if (target == null) {
            context.getSource().sendFailure(Component.literal("No dry and safe location was found for the drop."));
            return 0;
        }
        drop = DropState.pending(target, participants);
        buildPlatform(drop);
        saveState();
        sendPrepared(administrator, drop);
        context.getSource().sendSuccess(() -> Component.literal("Drop chest prepared at " + coordinates(drop.location)), false);
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        if (drop == null || drop.phase != Phase.PENDING) {
            context.getSource().sendFailure(Component.literal("There is no prepared drop to start."));
            return 0;
        }
        Chest chest = chest(drop);
        if (chest == null || isEmpty(chest)) {
            context.getSource().sendFailure(Component.literal("Fill the drop chest before starting it."));
            return 0;
        }
        drop.phase = Phase.ACTIVE;
        seedMilestones();
        createBossBar();
        broadcast("messages.started", placeholders(drop, null, 0));
        saveState();
        return 1;
    }

    private static int cancelCommand(CommandContext<CommandSourceStack> context) {
        if (drop == null) {
            context.getSource().sendFailure(Component.literal("There is no drop to cancel."));
            return 0;
        }
        cancel();
        context.getSource().sendSuccess(() -> Component.literal("Drop cancelled."), false);
        return 1;
    }

    private static int recalculate(CommandContext<CommandSourceStack> context) {
        if (drop != null) {
            cleanup(drop);
            clearState();
        }
        return locate(context);
    }

    public static void tick(MinecraftServer server) {
        if (drop == null) {
            return;
        }
        ticks++;
        World world = Bukkit.getWorld(drop.worldName);
        if (world == null || !isStructurePresent(world)) {
            cancel();
            return;
        }
        if (drop.phase != Phase.ACTIVE) {
            return;
        }
        updateBossBar();
        if (ticks % 20 == 0) {
            updateMilestones(world);
            Chest chest = chest(drop);
            if (chest == null) {
                cancel();
            } else if (isEmpty(chest)) {
                finish();
            }
        }
    }

    public static void clearBossBar() {
        if (drop != null && drop.bossBar != null) {
            drop.bossBar.removeAll();
            drop.bossBar = null;
        }
    }

    public static boolean blocksInteraction(ServerPlayer player, BlockPos position) {
        if (drop == null || !matches(player, position) || drop.phase != Phase.PENDING) {
            return false;
        }
        Player bukkit = ((ServerPlayerEntityBridge) player).bridge$getBukkitEntity();
        if (bukkit.hasPermission(PERMISSION)) {
            return false;
        }
        bukkit.sendMessage(ChatColor.RED + "This drop is being prepared by an administrator.");
        return true;
    }

    public static boolean protectsBlock(ServerPlayer player, BlockPos position) {
        return drop != null && matchesStructure(player, position);
    }

    public static void chestOpened(ServerPlayer player, BlockPos position) {
        if (drop == null || drop.phase != Phase.ACTIVE || !matches(player, position)) {
            return;
        }
        Player bukkit = ((ServerPlayerEntityBridge) player).bridge$getBukkitEntity();
        if (isDropAdmin(bukkit)) {
            return;
        }
        UUID opener = player.getUUID();
        if (drop.firstOpener == null) {
            drop.firstOpener = opener;
            drop.firstOpenerName = player.getScoreboardName();
            broadcast("messages.first-opened", placeholders(drop, player.getScoreboardName(), 0));
            saveState();
            return;
        }
        Player first = Bukkit.getPlayer(drop.firstOpener);
        if ((first == null || !first.isOnline()) && !drop.firstOpener.equals(opener)) {
            String departed = first == null ? drop.firstOpenerName : first.getName();
            broadcast("messages.reclaimed-after-leave", placeholders(drop, player.getScoreboardName(), 0) + "\u0000<previous-player>=" + departed);
            drop.firstOpener = opener;
            drop.firstOpenerName = player.getScoreboardName();
            saveState();
        }
    }

    private static void createBossBar() {
        if (drop.bossBar != null) {
            return;
        }
        drop.bossBar = Bukkit.createBossBar(format(settings.bossBarTitle, placeholders(drop, null, 0)), settings.bossBarColor, settings.bossBarStyle);
        drop.bossBar.setProgress(1.0);
        updateBossBar();
    }

    private static void updateBossBar() {
        if (drop.bossBar == null) {
            createBossBar();
            return;
        }
        World world = Bukkit.getWorld(drop.worldName);
        if (world == null) {
            return;
        }
        Set<UUID> visible = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world) || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            if (drop.participants.contains(player.getUniqueId()) || isDropAdmin(player)) {
                drop.bossBar.addPlayer(player);
                visible.add(player.getUniqueId());
            }
        }
        for (Player player : List.copyOf(drop.bossBar.getPlayers())) {
            if (!visible.contains(player.getUniqueId())) {
                drop.bossBar.removePlayer(player);
            }
        }
        drop.bossBar.setTitle(format(settings.bossBarTitle, placeholders(drop, null, 0)));
    }

    private static void seedMilestones() {
        World world = Bukkit.getWorld(drop.worldName);
        if (world == null) {
            return;
        }
        for (UUID participant : drop.participants) {
            Player player = Bukkit.getPlayer(participant);
            if (player == null || !player.isOnline() || !player.getWorld().equals(world) || isDropAdmin(player)) {
                continue;
            }
            double distance = horizontalDistance(player.getLocation(), drop.location);
            for (Integer milestone : settings.milestones) {
                if (distance <= milestone) {
                    drop.announcedMilestones.add(milestone);
                }
            }
        }
    }

    private static void updateMilestones(World world) {
        List<Integer> milestones = settings.milestones.stream().sorted((a, b) -> Integer.compare(b, a)).toList();
        for (UUID participant : drop.participants) {
            Player player = Bukkit.getPlayer(participant);
            if (player == null || !player.isOnline() || !player.getWorld().equals(world) || isDropAdmin(player)) {
                continue;
            }
            double distance = horizontalDistance(player.getLocation(), drop.location);
            boolean announced = false;
            for (Integer milestone : milestones) {
                if (distance <= milestone && drop.announcedMilestones.add(milestone)) {
                    broadcast("messages.nearby", placeholders(drop, player.getName(), (int) Math.ceil(distance)));
                    announced = true;
                }
            }
            if (announced) {
                saveState();
            }
        }
    }

    private static void finish() {
        broadcast("messages.finished", placeholders(drop, null, 0));
        cleanup(drop);
        clearState();
    }

    private static void cancel() {
        broadcast("messages.cancelled", placeholders(drop, null, 0));
        cleanup(drop);
        clearState();
    }

    private static void cleanup(DropState state) {
        clearBossBar();
        World world = Bukkit.getWorld(state.worldName);
        if (world == null) {
            return;
        }
        int floorY = state.location.getBlockY();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                Block block = world.getBlockAt(state.location.getBlockX() + x, floorY, state.location.getBlockZ() + z);
                if (block.getType() == Material.STONE_BRICKS) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        Block chest = world.getBlockAt(state.location.getBlockX(), floorY + 1, state.location.getBlockZ());
        if (chest.getType() == Material.CHEST) {
            chest.setType(Material.AIR, false);
        }
    }

    private static void clearState() {
        drop = null;
        YamlConfiguration config = ArclightConfiguration.get();
        config.set("drop-state", null);
        ArclightConfiguration.save();
    }

    private static void saveState() {
        YamlConfiguration config = ArclightConfiguration.get();
        config.set("drop-state", null);
        if (drop != null) {
            drop.save(config);
        }
        ArclightConfiguration.save();
    }

    private static void buildPlatform(DropState state) {
        World world = state.location.getWorld();
        int floorY = state.location.getBlockY();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                world.getBlockAt(state.location.getBlockX() + x, floorY, state.location.getBlockZ() + z).setType(Material.STONE_BRICKS, false);
            }
        }
        world.getBlockAt(state.location.getBlockX(), floorY + 1, state.location.getBlockZ()).setType(Material.CHEST, false);
    }

    private static Chest chest(DropState state) {
        World world = Bukkit.getWorld(state.worldName);
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(state.location.getBlockX(), state.location.getBlockY() + 1, state.location.getBlockZ());
        return block.getState() instanceof Chest chest ? chest : null;
    }

    private static boolean isEmpty(Chest chest) {
        for (ItemStack item : chest.getBlockInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(ServerPlayer player, BlockPos position) {
        return drop != null && ((ServerPlayerEntityBridge) player).bridge$getBukkitEntity().getWorld().getName().equals(drop.worldName)
            && position.getX() == drop.location.getBlockX()
            && position.getY() == drop.location.getBlockY() + 1
            && position.getZ() == drop.location.getBlockZ();
    }

    private static boolean matchesStructure(ServerPlayer player, BlockPos position) {
        if (drop == null || !((ServerPlayerEntityBridge) player).bridge$getBukkitEntity().getWorld().getName().equals(drop.worldName)) {
            return false;
        }
        int x = position.getX() - drop.location.getBlockX();
        int z = position.getZ() - drop.location.getBlockZ();
        return position.getY() == drop.location.getBlockY() && Math.abs(x) <= PLATFORM_RADIUS && Math.abs(z) <= PLATFORM_RADIUS || matches(player, position);
    }

    private static boolean isStructurePresent(World world) {
        Block chest = world.getBlockAt(drop.location.getBlockX(), drop.location.getBlockY() + 1, drop.location.getBlockZ());
        return chest.getType() == Material.CHEST;
    }

    private static boolean isDropAdmin(Player player) {
        return player.hasPermission(PERMISSION);
    }

    private static List<Player> competitors(World world) {
        List<Player> participants = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(world) && player.getGameMode() != org.bukkit.GameMode.SPECTATOR && !isDropAdmin(player)) {
                participants.add(player);
            }
        }
        return participants;
    }

    private static Location findSafeLocation(World world, List<Player> players) {
        Circle circle = enclosingCircle(players.stream().map(player -> new Point(player.getLocation().getX(), player.getLocation().getZ())).toList());
        int centerX = (int) Math.floor(circle.x);
        int centerZ = (int) Math.floor(circle.z);
        int preferredY = preferredY(players);
        Location best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestMaxDist = Double.POSITIVE_INFINITY;
        double bestCenterDist = Double.POSITIVE_INFINITY;
        for (int x = centerX - settings.searchRadius; x <= centerX + settings.searchRadius; x++) {
            for (int z = centerZ - settings.searchRadius; z <= centerZ + settings.searchRadius; z++) {
                Location candidate = safePlatform(world, x, z, preferredY);
                if (candidate == null) {
                    continue;
                }
                double minDist = Double.POSITIVE_INFINITY;
                double maxDist = Double.NEGATIVE_INFINITY;
                for (Player player : players) {
                    double distance = horizontalDistance(player.getLocation(), candidate);
                    minDist = Math.min(minDist, distance);
                    maxDist = Math.max(maxDist, distance);
                }
                double score = maxDist - minDist;
                double centerDist = distance(candidate.getX() + 0.5, candidate.getZ() + 0.5, circle.x, circle.z);
                if (score < bestScore || score == bestScore && maxDist < bestMaxDist || score == bestScore && maxDist == bestMaxDist && centerDist < bestCenterDist) {
                    best = candidate;
                    bestScore = score;
                    bestMaxDist = maxDist;
                    bestCenterDist = centerDist;
                }
            }
        }
        return best;
    }

    private static int preferredY(List<Player> players) {
        List<Integer> heights = new ArrayList<>(players.size());
        for (Player player : players) {
            heights.add(player.getLocation().getBlockY());
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    private static Location safePlatform(World world, int x, int z, int preferredY) {
        return switch (world.getEnvironment()) {
            case NETHER -> safeVerticalPlatform(world, x, z, preferredY, NETHER_VERTICAL_RANGE, Math.min(NETHER_CEILING_CAP, world.getMaxHeight() - 3), true);
            case THE_END -> safeVerticalPlatform(world, x, z, preferredY, END_VERTICAL_RANGE, world.getMaxHeight() - 3, false);
            default -> safeOverworldPlatform(world, x, z);
        };
    }

    private static Location safeOverworldPlatform(World world, int x, int z) {
        int floorY = world.getMinHeight();
        for (int offsetX = -PLATFORM_RADIUS; offsetX <= PLATFORM_RADIUS; offsetX++) {
            for (int offsetZ = -PLATFORM_RADIUS; offsetZ <= PLATFORM_RADIUS; offsetZ++) {
                int blockX = x + offsetX;
                int blockZ = z + offsetZ;
                int highest = world.getHighestBlockYAt(blockX, blockZ);
                Block ground = world.getBlockAt(blockX, highest, blockZ);
                if (!ground.getType().isSolid() || ground.isLiquid()) {
                    return null;
                }
                floorY = Math.max(floorY, highest + 1);
            }
        }
        return validateFloor(world, x, z, floorY, false);
    }

    private static Location safeVerticalPlatform(World world, int x, int z, int preferredY, int range, int maxFloorY, boolean nether) {
        int minFloorY = world.getMinHeight() + 1;
        int maxFloor = Math.min(maxFloorY, world.getMaxHeight() - 3);
        if (minFloorY > maxFloor) {
            return null;
        }
        preferredY = Math.max(minFloorY, Math.min(preferredY, maxFloor));
        for (int delta = 0; delta <= range; delta++) {
            Location above = validateFloor(world, x, z, preferredY + delta, nether);
            if (above != null) {
                return above;
            }
            if (delta > 0) {
                Location below = validateFloor(world, x, z, preferredY - delta, nether);
                if (below != null) {
                    return below;
                }
            }
        }
        return null;
    }

    private static Location validateFloor(World world, int x, int z, int floorY, boolean nether) {
        if (floorY < world.getMinHeight() + 1 || floorY + 2 >= world.getMaxHeight()) {
            return null;
        }
        if (nether && floorY > NETHER_CEILING_CAP) {
            return null;
        }
        for (int offsetX = -PLATFORM_RADIUS; offsetX <= PLATFORM_RADIUS; offsetX++) {
            for (int offsetZ = -PLATFORM_RADIUS; offsetZ <= PLATFORM_RADIUS; offsetZ++) {
                Block support = world.getBlockAt(x + offsetX, floorY - 1, z + offsetZ);
                Material type = support.getType();
                if (!type.isSolid() || support.isLiquid()) {
                    return null;
                }
                if (nether && type == Material.BEDROCK && floorY - 1 >= NETHER_CEILING_CAP) {
                    return null;
                }
                for (int y = floorY; y <= floorY + 2; y++) {
                    if (!world.getBlockAt(x + offsetX, y, z + offsetZ).getType().isAir()) {
                        return null;
                    }
                }
                if (!world.getWorldBorder().isInside(new Location(world, x + offsetX + 0.5, floorY, z + offsetZ + 0.5))) {
                    return null;
                }
            }
        }
        return new Location(world, x, floorY, z);
    }

    private static Circle enclosingCircle(List<Point> points) {
        Circle best = null;
        for (Point point : points) {
            best = smaller(best, new Circle(point.x, point.z, 0), points);
        }
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                Point a = points.get(first);
                Point b = points.get(second);
                best = smaller(best, new Circle((a.x + b.x) / 2.0, (a.z + b.z) / 2.0, distance(a.x, a.z, b.x, b.z) / 2.0), points);
            }
        }
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                for (int third = second + 1; third < points.size(); third++) {
                    Circle circle = through(points.get(first), points.get(second), points.get(third));
                    best = smaller(best, circle, points);
                }
            }
        }
        return best == null ? new Circle(0, 0, 0) : best;
    }

    private static Circle smaller(Circle current, Circle candidate, List<Point> points) {
        if (candidate == null || points.stream().anyMatch(point -> distance(candidate.x, candidate.z, point.x, point.z) > candidate.radius + 0.001)) {
            return current;
        }
        return current == null || candidate.radius < current.radius ? candidate : current;
    }

    private static Circle through(Point a, Point b, Point c) {
        double divisor = 2 * (a.x * (b.z - c.z) + b.x * (c.z - a.z) + c.x * (a.z - b.z));
        if (Math.abs(divisor) < 0.00001) return null;
        double aa = a.x * a.x + a.z * a.z;
        double bb = b.x * b.x + b.z * b.z;
        double cc = c.x * c.x + c.z * c.z;
        double x = (aa * (b.z - c.z) + bb * (c.z - a.z) + cc * (a.z - b.z)) / divisor;
        double z = (aa * (c.x - b.x) + bb * (a.x - c.x) + cc * (b.x - a.x)) / divisor;
        return new Circle(x, z, distance(x, z, a.x, a.z));
    }

    private static void sendPrepared(Player administrator, DropState state) {
        for (String line : settings.messages("messages.prepared", placeholders(state, null, 0))) {
            administrator.sendMessage(center(line));
        }
        String teleport = "/minecraft:tp @s " + state.location.getBlockX() + " " + (state.location.getBlockY() + 1) + " " + state.location.getBlockZ();
        Component teleportButton = Component.literal(ChatColor.translateAlternateColorCodes('&', "&e&l[ CLICK TO TELEPORT TO THE DROP ]"))
            .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, teleport)));
        Component recalculateButton = Component.literal(ChatColor.translateAlternateColorCodes('&', "&a&l[ RECALCULAR ]"))
            .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/drop recalculate")));
        Component cancelButton = Component.literal(ChatColor.translateAlternateColorCodes('&', "&c&l[ CANCELAR ]"))
            .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/drop cancel")));
        Component actions = Component.empty()
            .append(recalculateButton)
            .append(Component.literal(" "))
            .append(cancelButton);
        ((org.bukkit.craftbukkit.v.entity.CraftPlayer) administrator).getHandle().sendSystemMessage(teleportButton);
        ((org.bukkit.craftbukkit.v.entity.CraftPlayer) administrator).getHandle().sendSystemMessage(actions);
    }

    private static void broadcast(String path, String values) {
        if (drop == null) {
            return;
        }
        World world = Bukkit.getWorld(drop.worldName);
        if (world == null) {
            return;
        }
        Collection<Player> recipients = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world) || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            if (drop.participants.contains(player.getUniqueId()) || isDropAdmin(player)) {
                recipients.add(player);
            }
        }
        for (String line : settings.messages(path, values)) {
            String centered = center(line);
            recipients.forEach(player -> player.sendMessage(centered));
        }
    }

    private static String placeholders(DropState state, String player, int distance) {
        return "<x>=" + state.location.getBlockX() + "\u0000<y>=" + (state.location.getBlockY() + 1) + "\u0000<z>=" + state.location.getBlockZ()
            + "\u0000<player>=" + (player == null ? "" : player) + "\u0000<distance>=" + distance;
    }

    private static String format(String text, String values) {
        for (String pair : values.split("\u0000", -1)) {
            int index = pair.indexOf('=');
            if (index > 0) text = text.replace(pair.substring(0, index), pair.substring(index + 1));
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String center(String text) {
        String plain = ChatColor.stripColor(text);
        int spaces = Math.max(0, (CHAT_CENTER - plain.length() * 6) / 8);
        return " ".repeat(spaces) + text;
    }

    private static String coordinates(Location location) {
        return location.getBlockX() + ", " + (location.getBlockY() + 1) + ", " + location.getBlockZ();
    }

    private static double horizontalDistance(Location first, Location second) {
        return Math.hypot(first.getX() - second.getX(), first.getZ() - second.getZ());
    }

    private static double distance(double firstX, double firstZ, double secondX, double secondZ) {
        return Math.hypot(firstX - secondX, firstZ - secondZ);
    }

    private enum Phase { PENDING, ACTIVE }

    private record Point(double x, double z) { }

    private record Circle(double x, double z, double radius) { }

    private static final class DropState {
        private Phase phase;
        private final String worldName;
        private final Location location;
        private final Set<UUID> participants;
        private UUID firstOpener;
        private String firstOpenerName;
        private final Set<Integer> announcedMilestones = new HashSet<>();
        private BossBar bossBar;

        private DropState(Phase phase, Location location, Set<UUID> participants) {
            this.phase = phase;
            this.worldName = location.getWorld().getName();
            this.location = location;
            this.participants = participants;
        }

        private static DropState pending(Location location, List<Player> participants) {
            return new DropState(Phase.PENDING, location, participants.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet()));
        }

        private static DropState load(YamlConfiguration config) {
            String root = "drop-state";
            if (!config.contains(root + ".world")) return null;
            World world = Bukkit.getWorld(config.getString(root + ".world"));
            if (world == null) return null;
            try {
                DropState state = new DropState(Phase.valueOf(config.getString(root + ".phase", "PENDING")), new Location(world, config.getInt(root + ".x"), config.getInt(root + ".y"), config.getInt(root + ".z")), new HashSet<>());
                for (String value : config.getStringList(root + ".participants")) state.participants.add(UUID.fromString(value));
                String opener = config.getString(root + ".first-opener");
                if (opener != null) state.firstOpener = UUID.fromString(opener);
                state.firstOpenerName = config.getString(root + ".first-opener-name");
                state.announcedMilestones.addAll(config.getIntegerList(root + ".announced-milestones"));
                ConfigurationSection milestones = config.getConfigurationSection(root + ".milestones");
                if (milestones != null) {
                    for (String player : milestones.getKeys(false)) {
                        state.announcedMilestones.addAll(milestones.getIntegerList(player));
                    }
                }
                return state;
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private void save(YamlConfiguration config) {
            String root = "drop-state";
            config.set(root + ".phase", phase.name());
            config.set(root + ".world", worldName);
            config.set(root + ".x", location.getBlockX());
            config.set(root + ".y", location.getBlockY());
            config.set(root + ".z", location.getBlockZ());
            config.set(root + ".participants", participants.stream().map(UUID::toString).toList());
            config.set(root + ".first-opener", firstOpener == null ? null : firstOpener.toString());
            config.set(root + ".first-opener-name", firstOpenerName);
            config.set(root + ".announced-milestones", new ArrayList<>(announcedMilestones));
        }
    }

    private record DropSettings(int searchRadius, List<Integer> milestones, String bossBarTitle, BarColor bossBarColor, BarStyle bossBarStyle, YamlConfiguration config) {
        private static DropSettings defaults() {
            return new DropSettings(128, List.of(500, 250, 100, 50), "&6&lDROP &8| &fX: &e<x> &fY: &e<y> &fZ: &e<z>", BarColor.YELLOW, BarStyle.SEGMENTED_20, new YamlConfiguration());
        }

        private static DropSettings load(YamlConfiguration config) {
            DropSettings defaults = defaults();
            setDefault(config, "drop.safe-search-radius", defaults.searchRadius);
            setDefault(config, "drop.distance-milestones", defaults.milestones);
            setDefault(config, "drop.bossbar.title", defaults.bossBarTitle);
            setDefault(config, "drop.bossbar.color", defaults.bossBarColor.name());
            setDefault(config, "drop.bossbar.overlay", "NOTCHED_20");
            setDefault(config, "drop.messages.prepared", List.of("", "&8&m--------------------------------", "&6&lDROP PREPARED", "&7Fill the chest, then run &e/drop start&7.", "&fCoordinates: &e<x> <y> <z>", "&8&m--------------------------------", ""));
            setDefault(config, "drop.messages.started", List.of("", "&8&m--------------------------------", "&6&lDROP STARTED", "&7Find the chest at &e<x> <y> <z>&7!", "&8&m--------------------------------", ""));
            setDefault(config, "drop.messages.nearby", List.of("&e<player> &7is now &f<distance> blocks &7from the drop."));
            setDefault(config, "drop.messages.first-opened", List.of("&6<player> &7opened the drop chest first!"));
            setDefault(config, "drop.messages.reclaimed-after-leave", List.of("&6<player> &7claimed the remaining drop because &f<previous-player> &7left."));
            setDefault(config, "drop.messages.finished", List.of("&a&lDROP FINISHED &8| &7The chest has been emptied."));
            setDefault(config, "drop.messages.cancelled", List.of("&c&lDROP CANCELLED &8| &7The drop chest is no longer available."));
            return new DropSettings(Math.max(1, config.getInt("drop.safe-search-radius", defaults.searchRadius)), config.getIntegerList("drop.distance-milestones"), config.getString("drop.bossbar.title", defaults.bossBarTitle), enumValue(BarColor.class, config.getString("drop.bossbar.color"), defaults.bossBarColor), barStyle(config.getString("drop.bossbar.overlay"), defaults.bossBarStyle), config);
        }

        private List<String> messages(String path, String values) {
            return config.getStringList("drop." + path).stream().map(line -> format(line, values)).toList();
        }

        private static void setDefault(YamlConfiguration config, String path, Object value) {
            if (!config.contains(path)) config.set(path, value);
        }

        private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
            try {
                return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException exception) {
                return fallback;
            }
        }

        private static BarStyle barStyle(String value, BarStyle fallback) {
            if (value != null && value.startsWith("NOTCHED_")) value = "SEGMENTED_" + value.substring(8);
            return enumValue(BarStyle.class, value, fallback);
        }
    }
}
