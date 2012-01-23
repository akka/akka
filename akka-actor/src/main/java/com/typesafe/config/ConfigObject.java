/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

import java.util.Map;

/**
 * Subtype of {@link ConfigValue} representing an object (dictionary, map)
 * value, as in JSON's <code>{ "a" : 42 }</code> syntax.
 *
 * <p>
 * {@code ConfigObject} implements {@code java.util.Map<String, ConfigValue>} so
 * you can use it like a regular Java map. Or call {@link #unwrapped()} to
 * unwrap the map to a map with plain Java values rather than
 * {@code ConfigValue}.
 *
 * <p>
 * Like all {@link ConfigValue} subtypes, {@code ConfigObject} is immutable.
 * This makes it threadsafe and you never have to create "defensive copies." The
 * mutator methods from {@link java.util.Map} all throw
 * {@link java.lang.UnsupportedOperationException}.
 *
 * <p>
 * The {@link ConfigValue#valueType} method on an object returns
 * {@link ConfigValueType#OBJECT}.
 *
 * <p>
 * In most cases you want to use the {@link Config} interface rather than this
 * one. Call {@link #toConfig()} to convert a {@code ConfigObject} to a
 * {@code Config}.
 *
 * <p>
 * The API for a {@code ConfigObject} is in terms of keys, while the API for a
 * {@link Config} is in terms of path expressions. Conceptually,
 * {@code ConfigObject} is a tree of maps from keys to values, while a
 * {@code Config} is a one-level map from paths to values.
 *
 * <p>
 * Use {@link ConfigUtil#joinPath} and {@link ConfigUtil#splitPath} to convert
 * between path expressions and individual path elements (keys).
 *
 * <p>
 * A {@code ConfigObject} may contain null values, which will have
 * {@link ConfigValue#valueType()} equal to {@link ConfigValueType#NULL}. If
 * {@code get()} returns Java's null then the key was not present in the parsed
 * file (or wherever this value tree came from). If {@code get()} returns a
 * {@link ConfigValue} with type {@code ConfigValueType#NULL} then the key was
 * set to null explicitly in the config file.
 *
 * <p>
 * <em>Do not implement {@code ConfigObject}</em>; it should only be implemented
 * by the config library. Arbitrary implementations will not work because the
 * library internals assume a specific concrete implementation. Also, this
 * interface is likely to grow new methods over time, so third-party
 * implementations will break.
 */
public interface ConfigObject extends ConfigValue, Map<String, ConfigValue> {

    /**
     * Converts this object to a {@link Config} instance, enabling you to use
     * path expressions to find values in the object. This is a constant-time
     * operation (it is not proportional to the size of the object).
     *
     * @return a {@link Config} with this object as its root
     */
    Config toConfig();

    /**
     * Recursively unwraps the object, returning a map from String to whatever
     * plain Java values are unwrapped from the object's values.
     *
     * @return a {@link java.util.Map} containing plain Java objects
     */
    @Override
    Map<String, Object> unwrapped();

    @Override
    ConfigObject withFallback(ConfigMergeable other);

    /**
     * Gets a {@link ConfigValue} at the given key, or returns null if there is
     * no value. The returned {@link ConfigValue} may have
     * {@link ConfigValueType#NULL} or any other type, and the passed-in key
     * must be a key in this object, rather than a path expression.
     *
     * @param key
     *            key to look up
     *
     * @return the value at the key or null if none
     */
    @Override
    ConfigValue get(Object key);
}
