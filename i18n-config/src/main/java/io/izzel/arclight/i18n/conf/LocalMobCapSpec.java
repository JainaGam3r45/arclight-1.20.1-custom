package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

/**
 * Absolute local density overrides for {@code LocalMobCapCalculator}.
 * Values {@code < 0} mean use {@code vanillaMax * local-mob-cap-scale}.
 */
@ConfigSerializable
public class LocalMobCapSpec {

    @Setting("monster")
    private int monster = -1;

    @Setting("creature")
    private int creature = -1;

    @Setting("ambient")
    private int ambient = -1;

    @Setting("axolotls")
    private int axolotls = -1;

    @Setting("underground_water_creature")
    private int undergroundWaterCreature = -1;

    @Setting("water_creature")
    private int waterCreature = -1;

    @Setting("water_ambient")
    private int waterAmbient = -1;

    /**
     * @param categoryName vanilla serialized name (e.g. {@code monster})
     * @return absolute override, or a negative value to use scale
     */
    public int getOverride(String categoryName) {
        if (categoryName == null) {
            return -1;
        }
        return switch (categoryName) {
            case "monster" -> monster;
            case "creature" -> creature;
            case "ambient" -> ambient;
            case "axolotls" -> axolotls;
            case "underground_water_creature" -> undergroundWaterCreature;
            case "water_creature" -> waterCreature;
            case "water_ambient" -> waterAmbient;
            default -> -1;
        };
    }

    public int getMonster() {
        return monster;
    }

    public int getCreature() {
        return creature;
    }

    public int getAmbient() {
        return ambient;
    }

    public int getAxolotls() {
        return axolotls;
    }

    public int getUndergroundWaterCreature() {
        return undergroundWaterCreature;
    }

    public int getWaterCreature() {
        return waterCreature;
    }

    public int getWaterAmbient() {
        return waterAmbient;
    }
}
