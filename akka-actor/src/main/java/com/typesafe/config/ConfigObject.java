/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

import java.util.Map;

/**
 * A ConfigObject is a read-only configuration object, which may have nested
 * child objects. Implementations of ConfigObject should be immutable (at least
 * from the perspective of anyone using this interface) and thus thread-safe.
 *
 * In most cases you want to use the Config interface rather than this one. Call
 * toConfig() to convert a ConfigObject to a config.
 *
 * The API for a ConfigObject is in terms of keys, while the API for a Config is
 * in terms of path expressions. Conceptually, ConfigObject is a tree of maps
 * from keys to values, while a ConfigObject is a one-level map from paths to
 * values.
 *
 * Throughout the API, there is a distinction between "keys" and "paths". A key
 * is a key in a JSON object; it's just a string that's the key in a map. A
 * "path" is a parseable expression with a syntax and it refers to a series of
 * keys. A path is used to traverse nested ConfigObject by looking up each key
 * in the path. Path expressions are described in the spec for "HOCON", which
 * can be found at https://github.com/havocp/config/blob/master/HOCON.md; in
 * brief, a path is period-separated so "a.b.c" looks for key c in object b in
 * object a in the root object. Sometimes double quotes are needed around
 * special characters in path expressions.
 *
 * ConfigObject implements java.util.Map<String,ConfigValue> and all methods
 * work with keys, not path expressions.
 *
 * While ConfigObject implements the standard Java Map interface, the mutator
 * methods all throw UnsupportedOperationException. This Map is immutable.
 *
 * The Map may contain null values, which will have ConfigValue.valueType() ==
 * ConfigValueType.NULL. If get() returns Java's null then the key was not
 * present in the parsed file (or wherever this value tree came from). If get()
 * returns a ConfigValue with type ConfigValueType.NULL then the key was set to
 * null explicitly.
 */
public interface ConfigObject extends ConfigValue, Map<String, ConfigValue> {

    /**
     * Converts this object to a Config instance, enabling you to use path
     * expressions to find values in the object. This is a constant-time
     * operation (it is not proportional to the size of the object).
     *
     * @return
     */
    Config toConfig();

    /**
     * Recursively unwraps the object, returning a map from String to whatever
     * plain Java values are unwrapped from the object's values.
     */
    @Override
    Map<String, Object> unwrapped();

    @Override
    ConfigObject withFallback(ConfigMergeable other);

    /**
     * Gets a ConfigValue at the given key, or returns null if there is no
     * value. The returned ConfigValue may have ConfigValueType.NULL or any
     * other type, and the passed-in key must be a key in this object, rather
     * than a path expression.
     */
    @Override
    ConfigValue get(Object key);
}
