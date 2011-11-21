/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

public final class ConfigResolveOptions {
    private final boolean useSystemProperties;
    private final boolean useSystemEnvironment;

    private ConfigResolveOptions(boolean useSystemProperties,
            boolean useSystemEnvironment) {
        this.useSystemProperties = useSystemProperties;
        this.useSystemEnvironment = useSystemEnvironment;
    }

    public static ConfigResolveOptions defaults() {
        return new ConfigResolveOptions(true, true);
    }

    public static ConfigResolveOptions noSystem() {
        return new ConfigResolveOptions(false, false);
    }

    public ConfigResolveOptions setUseSystemProperties(boolean value) {
        return new ConfigResolveOptions(value, useSystemEnvironment);
    }

    public ConfigResolveOptions setUseSystemEnvironment(boolean value) {
        return new ConfigResolveOptions(useSystemProperties, value);
    }

    public boolean getUseSystemProperties() {
        return useSystemProperties;
    }

    public boolean getUseSystemEnvironment() {
        return useSystemEnvironment;
    }
}
