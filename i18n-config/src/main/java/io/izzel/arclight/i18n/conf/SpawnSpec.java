package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class SpawnSpec {

    @Setting("enabled")
    private boolean enabled = true;

    @Setting("skip-when-mspt-above")
    private double skipWhenMsptAbove = 45.0D;

    @Setting("max-chunks-per-tick")
    private int maxChunksPerTick = 64;

    @Setting("max-ms-per-tick")
    private double maxMsPerTick = 10.0D;

    @Setting("per-player-mob-spawns")
    private boolean perPlayerMobSpawns = true;

    @Setting("use-mob-spawn-range")
    private boolean useMobSpawnRange = true;

    @Setting("relaxed-when-players-at-most")
    private int relaxedWhenPlayersAtMost = 2;

    @Setting("relaxed-when-mspt-below")
    private double relaxedWhenMsptBelow = 30.0D;

    @Setting("local-mob-cap-scale")
    private double localMobCapScale = 1.0D;

    @Setting("local-mob-cap")
    private LocalMobCapSpec localMobCap = new LocalMobCapSpec();

    public boolean isEnabled() {
        return enabled;
    }

    public double getSkipWhenMsptAbove() {
        return skipWhenMsptAbove;
    }

    public int getMaxChunksPerTick() {
        return maxChunksPerTick;
    }

    public double getMaxMsPerTick() {
        return maxMsPerTick;
    }

    public boolean isPerPlayerMobSpawns() {
        return perPlayerMobSpawns;
    }

    public boolean isUseMobSpawnRange() {
        return useMobSpawnRange;
    }

    public int getRelaxedWhenPlayersAtMost() {
        return relaxedWhenPlayersAtMost;
    }

    public double getRelaxedWhenMsptBelow() {
        return relaxedWhenMsptBelow;
    }

    public double getLocalMobCapScale() {
        return localMobCapScale;
    }

    public LocalMobCapSpec getLocalMobCap() {
        return localMobCap != null ? localMobCap : new LocalMobCapSpec();
    }
}
