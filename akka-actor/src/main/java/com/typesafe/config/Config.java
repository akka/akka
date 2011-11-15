package com.typesafe.config;

import java.util.List;

/**
 * This class represents an immutable map from config paths to config values. It
 * also contains some static methods for creating configs.
 *
 * Throughout the API, there is a distinction between "keys" and "paths". A key
 * is a key in a JSON object; it's just a string that's the key in a map. A
 * "path" is a parseable expression with a syntax and it refers to a series of
 * keys. Path expressions are described in the spec for "HOCON", which can be
 * found at https://github.com/havocp/config/blob/master/HOCON.md; in brief, a
 * path is period-separated so "a.b.c" looks for key c in object b in object a
 * in the root object. Sometimes double quotes are needed around special
 * characters in path expressions.
 *
 * The API for a Config is in terms of path expressions, while the API for a
 * ConfigObject is in terms of keys. Conceptually, Config is a one-level map
 * from paths to values, while a ConfigObject is a tree of maps from keys to
 * values.
 *
 * Another difference between Config and ConfigObject is that conceptually,
 * ConfigValue with valueType() of ConfigValueType.NULL exist in a ConfigObject,
 * while a Config treats null values as if they were missing.
 *
 * Config is an immutable object and thus safe to use from multiple threads.
 *
 * The "getters" on a Config all work in the same way. They never return null,
 * nor do they return a ConfigValue with valueType() of ConfigValueType.NULL.
 * Instead, they throw ConfigException.Missing if the value is completely absent
 * or set to null. If the value is set to null, a subtype of
 * ConfigException.Missing called ConfigException.Null will be thrown.
 * ConfigException.WrongType will be thrown anytime you ask for a type and the
 * value has an incompatible type. Reasonable type conversions are performed for
 * you though.
 *
 * If you want to iterate over the contents of a Config, you have to get its
 * ConfigObject with toObject, and then iterate over the ConfigObject.
 *
 */
public interface Config extends ConfigMergeable {
    /**
     * Gets the config as a tree of ConfigObject. This is a constant-time
     * operation (it is not proportional to the number of values in the Config).
     *
     * @return
     */
    ConfigObject toObject();

    ConfigOrigin origin();

    @Override
    Config withFallback(ConfigMergeable other);

    @Override
    Config withFallbacks(ConfigMergeable... others);

    @Override
    ConfigObject toValue();

    /**
     * Checks whether a value is present and non-null at the given path. This
     * differs in two ways from ConfigObject.containsKey(): it looks for a path
     * expression, not a key; and it returns false for null values, while
     * containsKey() returns true indicating that the object contains a null
     * value for the key.
     *
     * If a path exists according to hasPath(), then getValue() will never throw
     * an exception. However, the typed getters, such as getInt(), will still
     * throw if the value is not convertible to the requested type.
     *
     * @param path
     *            the path expression
     * @return true if a non-null value is present at the path
     * @throws ConfigException.BadPath
     *             if the path expression is invalid
     */
    boolean hasPath(String path);

    boolean isEmpty();

    /**
     *
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to boolean
     */
    boolean getBoolean(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a number
     */
    Number getNumber(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to an int
     */
    int getInt(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a long
     */
    long getLong(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a double
     */
    double getDouble(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a string
     */
    String getString(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to an object
     */
    ConfigObject getObject(String path);

    /**
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a Config
     */
    Config getConfig(String path);

    /**
     * Gets the value at the path as an unwrapped Java boxed value (Boolean,
     * Integer, Long, etc.)
     *
     * @throws ConfigException.Missing
     *             if value is absent or null
     */
    Object getAnyRef(String path);

    /**
     * Gets the value at the given path, unless the value is a null value or
     * missing, in which case it throws just like the other getters. Use get()
     * from the Map interface if you want an unprocessed value.
     *
     * @param path
     * @return
     * @throws ConfigException.Missing
     *             if value is absent or null
     */
    ConfigValue getValue(String path);

    /**
     * Get value as a size in bytes (parses special strings like "128M"). The
     * size units are interpreted as for memory, not as for disk space, so they
     * are in powers of two.
     *
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to Long or String
     * @throws ConfigException.BadValue
     *             if value cannot be parsed as a memory size
     */
    Long getMemorySizeInBytes(String path);

    /**
     * Get value as a duration in milliseconds. If the value is already a
     * number, then it's left alone; if it's a string, it's parsed understanding
     * units suffixes like "10m" or "5ns"
     *
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to Long or String
     * @throws ConfigException.BadValue
     *             if value cannot be parsed as a number of milliseconds
     */
    Long getMilliseconds(String path);

    /**
     * Get value as a duration in nanoseconds. If the value is already a number
     * it's taken as milliseconds and converted to nanoseconds. If it's a
     * string, it's parsed understanding unit suffixes.
     *
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to Long or String
     * @throws ConfigException.BadValue
     *             if value cannot be parsed as a number of nanoseconds
     */
    Long getNanoseconds(String path);

    /**
     * Gets a list value (with any element type) as a ConfigList, which
     * implements java.util.List<ConfigValue>. Throws if the path is unset or
     * null.
     *
     * @param path
     *            the path to the list value.
     * @return the ConfigList at the path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a ConfigList
     */
    ConfigList getList(String path);

    List<Boolean> getBooleanList(String path);

    List<Number> getNumberList(String path);

    List<Integer> getIntList(String path);

    List<Long> getLongList(String path);

    List<Double> getDoubleList(String path);

    List<String> getStringList(String path);

    List<? extends ConfigObject> getObjectList(String path);

    List<? extends Config> getConfigList(String path);

    List<? extends Object> getAnyRefList(String path);

    List<Long> getMemorySizeInBytesList(String path);

    List<Long> getMillisecondsList(String path);

    List<Long> getNanosecondsList(String path);
}
