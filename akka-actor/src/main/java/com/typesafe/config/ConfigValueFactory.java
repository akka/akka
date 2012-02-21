/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

import java.util.Map;

import com.typesafe.config.impl.ConfigImpl;

/**
 * This class holds some static factory methods for building {@link ConfigValue}
 * instances. See also {@link ConfigFactory} which has methods for parsing files
 * and certain in-memory data structures.
 */
public final class ConfigValueFactory {
    private ConfigValueFactory() {
    }

    /**
     * Creates a ConfigValue from a plain Java boxed value, which may be a
     * Boolean, Number, String, Map, Iterable, or null. A Map must be a Map from
     * String to more values that can be supplied to fromAnyRef(). An Iterable
     * must iterate over more values that can be supplied to fromAnyRef(). A Map
     * will become a ConfigObject and an Iterable will become a ConfigList. If
     * the Iterable is not an ordered collection, results could be strange,
     * since ConfigList is ordered.
     *
     * <p>
     * In a Map passed to fromAnyRef(), the map's keys are plain keys, not path
     * expressions. So if your Map has a key "foo.bar" then you will get one
     * object with a key called "foo.bar", rather than an object with a key
     * "foo" containing another object with a key "bar".
     *
     * <p>
     * The originDescription will be used to set the origin() field on the
     * ConfigValue. It should normally be the name of the file the values came
     * from, or something short describing the value such as "default settings".
     * The originDescription is prefixed to error messages so users can tell
     * where problematic values are coming from.
     *
     * <p>
     * Supplying the result of ConfigValue.unwrapped() to this function is
     * guaranteed to work and should give you back a ConfigValue that matches
     * the one you unwrapped. The re-wrapped ConfigValue will lose some
     * information that was present in the original such as its origin, but it
     * will have matching values.
     *
     * <p>
     * This function throws if you supply a value that cannot be converted to a
     * ConfigValue, but supplying such a value is a bug in your program, so you
     * should never handle the exception. Just fix your program (or report a bug
     * against this library).
     *
     * @param object
     *            object to convert to ConfigValue
     * @param originDescription
     *            name of origin file or brief description of what the value is
     * @return a new value
     */
    public static ConfigValue fromAnyRef(Object object, String originDescription) {
        return ConfigImpl.fromAnyRef(object, originDescription);
    }

    /**
     * See the fromAnyRef() documentation for details. This is a typesafe
     * wrapper that only works on {@link java.util.Map} and returns
     * {@link ConfigObject} rather than {@link ConfigValue}.
     *
     * <p>
     * If your Map has a key "foo.bar" then you will get one object with a key
     * called "foo.bar", rather than an object with a key "foo" containing
     * another object with a key "bar". The keys in the map are keys; not path
     * expressions. That is, the Map corresponds exactly to a single
     * {@code ConfigObject}. The keys will not be parsed or modified, and the
     * values are wrapped in ConfigValue. To get nested {@code ConfigObject},
     * some of the values in the map would have to be more maps.
     *
     * <p>
     * See also {@link ConfigFactory#parseMap(Map,String)} which interprets the
     * keys in the map as path expressions.
     *
     * @param values
     * @param originDescription
     * @return a new {@link ConfigObject} value
     */
    public static ConfigObject fromMap(Map<String, ? extends Object> values,
            String originDescription) {
        return (ConfigObject) fromAnyRef(values, originDescription);
    }

    /**
     * See the fromAnyRef() documentation for details. This is a typesafe
     * wrapper that only works on {@link java.util.Iterable} and returns
     * {@link ConfigList} rather than {@link ConfigValue}.
     *
     * @param values
     * @param originDescription
     * @return a new {@link ConfigList} value
     */
    public static ConfigList fromIterable(Iterable<? extends Object> values,
            String originDescription) {
        return (ConfigList) fromAnyRef(values, originDescription);
    }

    /**
     * See the other overload {@link #fromAnyRef(Object,String)} for details,
     * this one just uses a default origin description.
     *
     * @param object
     * @return a new {@link ConfigValue}
     */
    public static ConfigValue fromAnyRef(Object object) {
        return fromAnyRef(object, null);
    }

    /**
     * See the other overload {@link #fromMap(Map,String)} for details, this one
     * just uses a default origin description.
     *
     * <p>
     * See also {@link ConfigFactory#parseMap(Map)} which interprets the keys in
     * the map as path expressions.
     *
     * @param values
     * @return a new {@link ConfigObject}
     */
    public static ConfigObject fromMap(Map<String, ? extends Object> values) {
        return fromMap(values, null);
    }

    /**
     * See the other overload of {@link #fromIterable(Iterable, String)} for
     * details, this one just uses a default origin description.
     *
     * @param values
     * @return a new {@link ConfigList}
     */
    public static ConfigList fromIterable(Iterable<? extends Object> values) {
        return fromIterable(values, null);
    }
}
