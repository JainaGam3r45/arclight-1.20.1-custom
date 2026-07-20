package io.izzel.arclight.common.bridge.bukkit;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;

public interface CraftServerBridge {

    void bridge$setPlayerList(PlayerList playerList);

    void bridge$removeWorld(ServerLevel world);

    /**
     * Reload bukkit.yml spawn-limits / ticks-per and push ticks-per into loaded worlds.
     */
    void bridge$reloadSpawnSettings();

    /**
     * Reload bukkit.yml (and commands.yml aliases) without unloading plugins.
     */
    void bridge$reloadBukkitConfig();

    /**
     * Reload spigot.yml globals and per-world SpigotWorldConfig without unloading plugins.
     *
     * @return {@code true} if settings.bungeecord changed (caller should warn)
     */
    boolean bridge$reloadSpigotConfig();
}
