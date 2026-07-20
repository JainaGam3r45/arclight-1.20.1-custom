package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class ProxiesSpec {

    @Setting("velocity")
    private VelocitySpec velocity = new VelocitySpec();

    public VelocitySpec getVelocity() {
        return velocity != null ? velocity : new VelocitySpec();
    }
}
