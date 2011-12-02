/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

/**
 * A set of options related to resolving substitutions. Substitutions use the
 * <code>${foo.bar}</code> syntax and are documented in the <a
 * href="https://github.com/havocp/config/blob/master/HOCON.md">HOCON</a> spec.
 *
 * <p>
 * This object is immutable, so the "setters" return a new object.
 *
 * <p>
 * Here is an example of creating a custom {@code ConfigResolveOptions}:
 *
 * <pre>
 *     ConfigResolveOptions options = ConfigResolveOptions.defaults()
 *         .setUseSystemProperties(false)
 *         .setUseSystemEnvironment(false)
 * </pre>
 *
 * <p>
 * In addition to {@link ConfigResolveOptions#defaults}, there's a prebuilt
 * {@link ConfigResolveOptions#noSystem} which avoids looking at any system
 * properties or environment variables.
 */
public final class ConfigResolveOptions {
    private final boolean useSystemProperties;
    private final boolean useSystemEnvironment;

    private ConfigResolveOptions(boolean useSystemProperties,
            boolean useSystemEnvironment) {
        this.useSystemProperties = useSystemProperties;
        this.useSystemEnvironment = useSystemEnvironment;
    }

    /**
     * Returns the default resolve options.
     *
     * @return the default resolve options
     */
    public static ConfigResolveOptions defaults() {
        return new ConfigResolveOptions(true, true);
    }

    /**
     * Returns resolve options that disable any reference to "system" data
     * (system properties or environment variables).
     *
     * @return the resolve options with system properties and env variables
     *         disabled
     */
    public static ConfigResolveOptions noSystem() {
        return defaults().setUseSystemEnvironment(false).setUseSystemProperties(false);
    }

    /**
     * Returns options with use of Java system properties set to the given
     * value.
     *
     * @param value
     *            true to resolve substitutions falling back to Java system
     *            properties.
     * @return options with requested setting for use of system properties
     */
    public ConfigResolveOptions setUseSystemProperties(boolean value) {
        return new ConfigResolveOptions(value, useSystemEnvironment);
    }

    /**
     * Returns options with use of environment variables set to the given value.
     *
     * @param value
     *            true to resolve substitutions falling back to environment
     *            variables.
     * @return options with requested setting for use of environment variables
     */
    public ConfigResolveOptions setUseSystemEnvironment(boolean value) {
        return new ConfigResolveOptions(useSystemProperties, value);
    }

    /**
     * Returns whether the options enable use of system properties. This method
     * is mostly used by the config lib internally, not by applications.
     *
     * @return true if system properties should be used
     */
    public boolean getUseSystemProperties() {
        return useSystemProperties;
    }

    /**
     * Returns whether the options enable use of system environment variables.
     * This method is mostly used by the config lib internally, not by
     * applications.
     *
     * @return true if environment variables should be used
     */
    public boolean getUseSystemEnvironment() {
        return useSystemEnvironment;
    }
}
