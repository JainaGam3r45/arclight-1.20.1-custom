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
}
