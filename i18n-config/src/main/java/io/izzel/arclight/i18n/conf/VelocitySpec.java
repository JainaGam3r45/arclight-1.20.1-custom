package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class VelocitySpec {

    @Setting("enabled")
    private boolean enabled;

    @Setting("online-mode")
    private boolean onlineMode = true;

    @Setting("secret")
    private String secret = "";

    public boolean isEnabled() {
        return enabled && secret != null && !secret.isEmpty();
    }

    public boolean isConfiguredEnabled() {
        return enabled;
    }

    public boolean isOnlineMode() {
        return onlineMode;
    }

    public String getSecret() {
        return secret == null ? "" : secret;
    }
}
