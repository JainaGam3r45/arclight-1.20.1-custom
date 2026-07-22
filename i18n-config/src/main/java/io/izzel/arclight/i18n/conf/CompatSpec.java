package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ConfigSerializable
public class CompatSpec {

    @Setting("material-property-overrides")
    private Map<String, MaterialPropertySpec> materials;

    @Setting("entity-property-overrides")
    private Map<String, EntityPropertySpec> entities;

    @Setting("symlink-world")
    private boolean symlinkWorld;

    @Setting("extra-logic-worlds")
    private List<String> extraLogicWorlds;

    @Setting("forward-permission")
    private String forwardPermission;

    @Setting("valid-username-regex")
    private String validUsernameRegex;

    @Setting("lenient-item-tag-match")
    private boolean lenientItemTagMatch;

    @Setting("isolate-plugin-class-loaders")
    private List<String> isolatePluginClassLoaders;

    @Setting("isolate-adventure-from-modloader")
    private boolean isolateAdventureFromModloader;

    public Map<String, MaterialPropertySpec> getMaterials() {
        return materials == null ? Collections.emptyMap() : materials;
    }

    public Optional<MaterialPropertySpec> getMaterial(String key) {
        return Optional.ofNullable(getMaterials().get(key));
    }

    public Map<String, EntityPropertySpec> getEntities() {
        return entities == null ? Collections.emptyMap() : entities;
    }

    public Optional<EntityPropertySpec> getEntity(String key) {
        return Optional.ofNullable(getEntities().get(key));
    }

    public boolean isSymlinkWorld() {
        return symlinkWorld;
    }

    public List<String> getExtraLogicWorlds() {
        return extraLogicWorlds == null ? Collections.emptyList() : extraLogicWorlds;
    }

    public boolean isForwardPermission() {
        return Objects.equals(forwardPermission, "true");
    }

    public boolean isForwardPermissionReverse() {
        return Objects.equals(forwardPermission, "reverse");
    }

    public String getValidUsernameRegex() {
        return validUsernameRegex;
    }

    public boolean isLenientItemTagMatch() {
        return lenientItemTagMatch;
    }

    public boolean isIsolatedPluginClassLoaders(String name) {
        List<String> prefixes = isolatePluginClassLoaders == null
            ? Collections.emptyList() : isolatePluginClassLoaders;
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAdventureIsolatedFromML() {
        return isolateAdventureFromModloader;
    }
}
