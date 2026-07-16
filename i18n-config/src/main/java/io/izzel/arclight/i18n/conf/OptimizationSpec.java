package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class OptimizationSpec {

    @Setting("cache-plugin-class")
    private boolean cachePluginClass;

    @Setting("disable-data-fixer")
    private boolean disableDFU;

    @Setting("goal-selector-update-interval")
    private int goalSelectorInterval;

    @Setting("use-activation-and-tracking-range")
    private boolean useActivationAndTrackingRange;

    @Setting("max-entity-collisions")
    private int maxEntityCollisions = 8;

    @Setting("quiet-startup")
    private boolean quietStartup;

    @Setting("spawn")
    private SpawnSpec spawn = new SpawnSpec();

    public boolean useActivationAndTrackingRange() {
        return useActivationAndTrackingRange;
    }

    public SpawnSpec getSpawn() {
        return spawn != null ? spawn : new SpawnSpec();
    }

    public boolean isCachePluginClass() {
        return cachePluginClass;
    }

    public boolean isDisableDFU() {
        return disableDFU;
    }

    public int getGoalSelectorInterval() {
        return goalSelectorInterval;
    }

    /**
     * Paper-style collision cap. Default {@code 8} (classic Spigot).
     * Values {@code <= 0} disable entity-entity push collisions.
     */
    public int getMaxEntityCollisions() {
        return maxEntityCollisions;
    }

    public boolean isQuietStartup() {
        return quietStartup;
    }
}
