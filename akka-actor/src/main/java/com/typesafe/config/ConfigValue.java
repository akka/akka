/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

/**
 * Interface implemented by any configuration value. From the perspective of
 * users of this interface, the object is immutable. It is therefore safe to use
 * from multiple threads.
 */
public interface ConfigValue extends ConfigMergeable {
    /**
     * The origin of the value, for debugging and error messages.
     *
     * @return where the value came from
     */
    ConfigOrigin origin();

    /**
     * The type of the value; matches the JSON type schema.
     *
     * @return value's type
     */
    ConfigValueType valueType();

    /**
     * Returns the config value as a plain Java boxed value, should be a String,
     * Number, etc. matching the valueType() of the ConfigValue. If the value is
     * a ConfigObject or ConfigList, it is recursively unwrapped.
     */
    Object unwrapped();

    @Override
    ConfigValue withFallback(ConfigMergeable other);
}
