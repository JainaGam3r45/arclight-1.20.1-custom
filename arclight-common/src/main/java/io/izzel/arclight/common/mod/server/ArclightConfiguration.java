package io.izzel.arclight.common.mod.server;

import io.izzel.arclight.i18n.ArclightConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Map;

/**
 * Bukkit-facing view of the unified {@code arclight.yml}.
 * Load/save go through Configurate so comments are preserved on disk.
 */
public final class ArclightConfiguration {

    private static YamlConfiguration configuration;

    private ArclightConfiguration() {
    }

    public static synchronized YamlConfiguration get() {
        if (configuration == null) {
            configuration = fromConfigurate();
        }
        return configuration;
    }

    public static synchronized YamlConfiguration reload() {
        configuration = fromConfigurate();
        return configuration;
    }

    /**
     * Rebuild the Bukkit view after {@link ArclightConfig#reload()}.
     */
    public static synchronized YamlConfiguration refreshFromDisk() throws Exception {
        ArclightConfig.reload();
        configuration = fromConfigurate();
        return configuration;
    }

    public static synchronized void save() {
        try {
            if (configuration == null) {
                return;
            }
            ArclightConfig.mergeFromYamlString(configuration.saveToString());
            configuration = fromConfigurate();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save arclight.yml", exception);
        }
    }

    public static synchronized boolean copySection(YamlConfiguration source, String sourcePath, String targetPath) {
        ConfigurationSection sourceSection = source.getConfigurationSection(sourcePath);
        if (sourceSection == null || get().contains(targetPath)) {
            return false;
        }
        copySection(sourceSection, get().createSection(targetPath));
        return true;
    }

    private static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection section) {
                copySection(section, target.createSection(key));
            } else {
                target.set(key, value);
            }
        }
    }

    private static YamlConfiguration fromConfigurate() {
        try {
            String yaml = ArclightConfig.toYamlString();
            YamlConfiguration bukkit = new YamlConfiguration();
            bukkit.loadFromString(yaml);
            return bukkit;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read arclight.yml", exception);
        }
    }

    /**
     * Apply leaf values into Configurate without a full Bukkit dump.
     */
    public static synchronized void putAll(Map<String, Object> values) {
        try {
            ArclightConfig.putAll(values);
            configuration = fromConfigurate();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to update arclight.yml", exception);
        }
    }
}
