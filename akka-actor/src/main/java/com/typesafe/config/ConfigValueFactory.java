package com.typesafe.config;

import java.util.Collection;
import java.util.Map;

import com.typesafe.config.impl.ConfigImpl;

/**
 * This class holds some static factory methods for building ConfigValue. See
 * also ConfigFactory which has methods for parsing files and certain in-memory
 * data structures.
 */
public final class ConfigValueFactory {
    /**
     * Creates a ConfigValue from a plain Java boxed value, which may be a
     * Boolean, Number, String, Map, Iterable, or null. A Map must be a Map from
     * String to more values that can be supplied to fromAnyRef(). An Iterable
     * must iterate over more values that can be supplied to fromAnyRef(). A Map
     * will become a ConfigObject and an Iterable will become a ConfigList. If
     * the Iterable is not an ordered collection, results could be strange,
     * since ConfigList is ordered.
     *
     * In a Map passed to fromAnyRef(), the map's keys are plain keys, not path
     * expressions. So if your Map has a key "foo.bar" then you will get one
     * object with a key called "foo.bar", rather than an object with a key
     * "foo" containing another object with a key "bar".
     *
     * The originDescription will be used to set the origin() field on the
     * ConfigValue. It should normally be the name of the file the values came
     * from, or something short describing the value such as "default settings".
     * The originDescription is prefixed to error messages so users can tell
     * where problematic values are coming from.
     *
     * Supplying the result of ConfigValue.unwrapped() to this function is
     * guaranteed to work and should give you back a ConfigValue that matches
     * the one you unwrapped. The re-wrapped ConfigValue will lose some
     * information that was present in the original such as its origin, but it
     * will have matching values.
     *
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
     * wrapper that only works on Map and returns ConfigObject rather than
     * ConfigValue.
     *
     * If your Map has a key "foo.bar" then you will get one object with a key
     * called "foo.bar", rather than an object with a key "foo" containing
     * another object with a key "bar". The keys in the map are keys; not path
     * expressions. That is, the Map corresponds exactly to a single
     * ConfigObject. The keys will not be parsed or modified, and the values are
     * wrapped in ConfigValue. To get nested ConfigObject, some of the values in
     * the map would have to be more maps.
     *
     * There is a separate fromPathMap() that interprets the keys in the map as
     * path expressions.
     *
     * @param values
     * @param originDescription
     * @return
     */
    public static ConfigObject fromMap(Map<String, ? extends Object> values,
            String originDescription) {
        return (ConfigObject) fromAnyRef(values, originDescription);
    }

    /**
     * See the fromAnyRef() documentation for details. This is a typesafe
     * wrapper that only works on Iterable and returns ConfigList rather than
     * ConfigValue.
     *
     * @param values
     * @param originDescription
     * @return
     */
    public static ConfigList fromIterable(Iterable<? extends Object> values,
            String originDescription) {
        return (ConfigList) fromAnyRef(values, originDescription);
    }

    /**
     * See the other overload of fromAnyRef() for details, this one just uses a
     * default origin description.
     *
     * @param object
     * @return
     */
    public static ConfigValue fromAnyRef(Object object) {
        return fromAnyRef(object, null);
    }

    /**
     * See the other overload of fromMap() for details, this one just uses a
     * default origin description.
     *
     * @param values
     * @return
     */
    public static ConfigObject fromMap(Map<String, ? extends Object> values) {
        return fromMap(values, null);
    }

    /**
     * See the other overload of fromIterable() for details, this one just uses
     * a default origin description.
     *
     * @param values
     * @return
     */
    public static ConfigList fromIterable(Collection<? extends Object> values) {
        return fromIterable(values, null);
    }
}
