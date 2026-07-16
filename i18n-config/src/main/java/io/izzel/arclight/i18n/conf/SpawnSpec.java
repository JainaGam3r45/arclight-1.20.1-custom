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
    private int maxChunksPerTick = 8;

    @Setting("max-ms-per-tick")
    private double maxMsPerTick = 2.5D;

    @Setting("per-player-mob-spawns")
    private boolean perPlayerMobSpawns = true;

    @Setting("use-mob-spawn-range")
    private boolean useMobSpawnRange = true;

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
}
