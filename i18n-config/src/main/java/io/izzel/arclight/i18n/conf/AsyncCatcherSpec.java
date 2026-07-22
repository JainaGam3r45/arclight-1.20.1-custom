package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.util.Collections;
import java.util.Map;

@ConfigSerializable
public class AsyncCatcherSpec {

    @Setting("dump")
    private boolean dump;

    @Setting("warn")
    private boolean warn;

    @Setting("defaultOperation")
    private Operation defaultOp;

    @Setting("overrides")
    private Map<String, Operation> overrides;

    @Setting("log-overrides")
    private Map<String, LogLevel> logOverrides;

    public boolean isDump() {
        return dump;
    }

    public boolean isWarn() {
        return warn;
    }

    public Operation getDefaultOp() {
        return defaultOp;
    }

    public Map<String, Operation> getOverrides() {
        return overrides == null ? Collections.emptyMap() : overrides;
    }

    public Map<String, LogLevel> getLogOverrides() {
        return logOverrides == null ? Collections.emptyMap() : logOverrides;
    }

    public enum Operation {
        NONE, DISPATCH, BLOCK, EXCEPTION
    }

    public enum LogLevel {
        OFF, DEBUG, WARN
    }
}
