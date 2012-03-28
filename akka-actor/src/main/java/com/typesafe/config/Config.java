/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable map from config paths to config values.
 *
 * <p>
 * Contrast with {@link ConfigObject} which is a map from config <em>keys</em>,
 * rather than paths, to config values. A {@code Config} contains a tree of
 * {@code ConfigObject}, and {@link Config#root()} returns the tree's root
 * object.
 *
 * <p>
 * Throughout the API, there is a distinction between "keys" and "paths". A key
 * is a key in a JSON object; it's just a string that's the key in a map. A
 * "path" is a parseable expression with a syntax and it refers to a series of
 * keys. Path expressions are described in the <a
 * href="https://github.com/typesafehub/config/blob/master/HOCON.md">spec for
 * Human-Optimized Config Object Notation</a>. In brief, a path is
 * period-separated so "a.b.c" looks for key c in object b in object a in the
 * root object. Sometimes double quotes are needed around special characters in
 * path expressions.
 *
 * <p>
 * The API for a {@code Config} is in terms of path expressions, while the API
 * for a {@code ConfigObject} is in terms of keys. Conceptually, {@code Config}
 * is a one-level map from <em>paths</em> to values, while a
 * {@code ConfigObject} is a tree of nested maps from <em>keys</em> to values.
 *
 * <p>
 * Use {@link ConfigUtil#joinPath} and {@link ConfigUtil#splitPath} to convert
 * between path expressions and individual path elements (keys).
 *
 * <p>
 * Another difference between {@code Config} and {@code ConfigObject} is that
 * conceptually, {@code ConfigValue}s with a {@link ConfigValue#valueType()
 * valueType()} of {@link ConfigValueType#NULL NULL} exist in a
 * {@code ConfigObject}, while a {@code Config} treats null values as if they
 * were missing.
 *
 * <p>
 * {@code Config} is an immutable object and thus safe to use from multiple
 * threads. There's never a need for "defensive copies."
 *
 * <p>
 * The "getters" on a {@code Config} all work in the same way. They never return
 * null, nor do they return a {@code ConfigValue} with
 * {@link ConfigValue#valueType() valueType()} of {@link ConfigValueType#NULL
 * NULL}. Instead, they throw {@link ConfigException.Missing} if the value is
 * completely absent or set to null. If the value is set to null, a subtype of
 * {@code ConfigException.Missing} called {@link ConfigException.Null} will be
 * thrown. {@link ConfigException.WrongType} will be thrown anytime you ask for
 * a type and the value has an incompatible type. Reasonable type conversions
 * are performed for you though.
 *
 * <p>
 * If you want to iterate over the contents of a {@code Config}, you can get its
 * {@code ConfigObject} with {@link #root()}, and then iterate over the
 * {@code ConfigObject} (which implements <code>java.util.Map</code>). Or, you
 * can use {@link #entrySet()} which recurses the object tree for you and builds
 * up a <code>Set</code> of all path-value pairs where the value is not null.
 *
 * <p>
 * <em>Do not implement {@code Config}</em>; it should only be implemented by
 * the config library. Arbitrary implementations will not work because the
 * library internals assume a specific concrete implementation. Also, this
 * interface is likely to grow new methods over time, so third-party
 * implementations will break.
 */
public interface Config extends ConfigMergeable {
    /**
     * Gets the {@code Config} as a tree of {@link ConfigObject}. This is a
     * constant-time operation (it is not proportional to the number of values
     * in the {@code Config}).
     *
     * @return the root object in the configuration
     */
    ConfigObject root();

    /**
     * Gets the origin of the {@code Config}, which may be a file, or a file
     * with a line number, or just a descriptive phrase.
     *
     * @return the origin of the {@code Config} for use in error messages
     */
    ConfigOrigin origin();

    @Override
    Config withFallback(ConfigMergeable other);

    /**
     * Returns a replacement config with all substitutions (the
     * <code>${foo.bar}</code> syntax, see <a
     * href="https://github.com/typesafehub/config/blob/master/HOCON.md">the
     * spec</a>) resolved. Substitutions are looked up using this
     * <code>Config</code> as the root object, that is, a substitution
     * <code>${foo.bar}</code> will be replaced with the result of
     * <code>getValue("foo.bar")</code>.
     *
     * <p>
     * This method uses {@link ConfigResolveOptions#defaults()}, there is
     * another variant {@link Config#resolve(ConfigResolveOptions)} which lets
     * you specify non-default options.
     *
     * <p>
     * A given {@link Config} must be resolved before using it to retrieve
     * config values, but ideally should be resolved one time for your entire
     * stack of fallbacks (see {@link Config#withFallback}). Otherwise, some
     * substitutions that could have resolved with all fallbacks available may
     * not resolve, which will be a user-visible oddity.
     *
     * <p>
     * <code>resolve()</code> should be invoked on root config objects, rather
     * than on a subtree (a subtree is the result of something like
     * <code>config.getConfig("foo")</code>). The problem with
     * <code>resolve()</code> on a subtree is that substitutions are relative to
     * the root of the config and the subtree will have no way to get values
     * from the root. For example, if you did
     * <code>config.getConfig("foo").resolve()</code> on the below config file,
     * it would not work:
     *
     * <pre>
     *   common-value = 10
     *   foo {
     *      whatever = ${common-value}
     *   }
     * </pre>
     *
     * @return an immutable object with substitutions resolved
     * @throws ConfigException.UnresolvedSubstitution
     *             if any substitutions refer to nonexistent paths
     * @throws ConfigException
     *             some other config exception if there are other problems
     */
    Config resolve();

    /**
     * Like {@link Config#resolve()} but allows you to specify non-default
     * options.
     *
     * @param options
     *            resolve options
     * @return the resolved <code>Config</code>
     */
    Config resolve(ConfigResolveOptions options);

    /**
     * Validates this config against a reference config, throwing an exception
     * if it is invalid. The purpose of this method is to "fail early" with a
     * comprehensive list of problems; in general, anything this method can find
     * would be detected later when trying to use the config, but it's often
     * more user-friendly to fail right away when loading the config.
     *
     * <p>
     * Using this method is always optional, since you can "fail late" instead.
     *
     * <p>
     * You must restrict validation to paths you "own" (those whose meaning are
     * defined by your code module). If you validate globally, you may trigger
     * errors about paths that happen to be in the config but have nothing to do
     * with your module. It's best to allow the modules owning those paths to
     * validate them. Also, if every module validates only its own stuff, there
     * isn't as much redundant work being done.
     *
     * <p>
     * If no paths are specified in <code>checkValid()</code>'s parameter list,
     * validation is for the entire config.
     *
     * <p>
     * If you specify paths that are not in the reference config, those paths
     * are ignored. (There's nothing to validate.)
     *
     * <p>
     * Here's what validation involves:
     *
     * <ul>
     * <li>All paths found in the reference config must be present in this
     * config or an exception will be thrown.
     * <li>
     * Some changes in type from the reference config to this config will cause
     * an exception to be thrown. Not all potential type problems are detected,
     * in particular it's assumed that strings are compatible with everything
     * except objects and lists. This is because string types are often "really"
     * some other type (system properties always start out as strings, or a
     * string like "5ms" could be used with {@link #getMilliseconds}). Also,
     * it's allowed to set any type to null or override null with any type.
     * <li>
     * Any unresolved substitutions in this config will cause a validation
     * failure; both the reference config and this config should be resolved
     * before validation. If the reference config is unresolved, it's a bug in
     * the caller of this method.
     * </ul>
     *
     * <p>
     * If you want to allow a certain setting to have a flexible type (or
     * otherwise want validation to be looser for some settings), you could
     * either remove the problematic setting from the reference config provided
     * to this method, or you could intercept the validation exception and
     * screen out certain problems. Of course, this will only work if all other
     * callers of this method are careful to restrict validation to their own
     * paths, as they should be.
     *
     * <p>
     * If validation fails, the thrown exception contains a list of all problems
     * found. See {@link ConfigException.ValidationFailed#problems}. The
     * exception's <code>getMessage()</code> will have all the problems
     * concatenated into one huge string, as well.
     *
     * <p>
     * Again, <code>checkValid()</code> can't guess every domain-specific way a
     * setting can be invalid, so some problems may arise later when attempting
     * to use the config. <code>checkValid()</code> is limited to reporting
     * generic, but common, problems such as missing settings and blatant type
     * incompatibilities.
     *
     * @param reference
     *            a reference configuration
     * @param restrictToPaths
     *            only validate values underneath these paths that your code
     *            module owns and understands
     * @throws ConfigException.ValidationFailed
     *             if there are any validation issues
     * @throws ConfigException.NotResolved
     *             if this config is not resolved
     * @throws ConfigException.BugOrBroken
     *             if the reference config is unresolved or caller otherwise
     *             misuses the API
     */
    void checkValid(Config reference, String... restrictToPaths);

    /**
     * Checks whether a value is present and non-null at the given path. This
     * differs in two ways from {@code Map.containsKey()} as implemented by
     * {@link ConfigObject}: it looks for a path expression, not a key; and it
     * returns false for null values, while {@code containsKey()} returns true
     * indicating that the object contains a null value for the key.
     *
     * <p>
     * If a path exists according to {@link #hasPath(String)}, then
     * {@link #getValue(String)} will never throw an exception. However, the
     * typed getters, such as {@link #getInt(String)}, will still throw if the
     * value is not convertible to the requested type.
     *
     * @param path
     *            the path expression
     * @return true if a non-null value is present at the path
     * @throws ConfigException.BadPath
     *             if the path expression is invalid
     */
    boolean hasPath(String path);

    /**
     * Returns true if the {@code Config}'s root object contains no key-value
     * pairs.
     *
     * @return true if the configuration is empty
     */
    boolean isEmpty();

    /**
     * Returns the set of path-value pairs, excluding any null values, found by
     * recursing {@link #root() the root object}. Note that this is very
     * different from <code>root().entrySet()</code> which returns the set of
     * immediate-child keys in the root object and includes null values.
     *
     * @return set of paths with non-null values, built up by recursing the
     *         entire tree of {@link ConfigObject}
     */
    Set<Map.Entry<String, ConfigValue>> entrySet();

    /**
     *
     * @param path
     *            path expression
     * @return the boolean value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to boolean
     */
    boolean getBoolean(String path);

    /**
     * @param path
     *            path expression
     * @return the numeric value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a number
     */
    Number getNumber(String path);

    /**
     * @param path
     *            path expression
     * @return the 32-bit integer value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to an int (for example it is out
     *             of range, or it's a boolean value)
     */
    int getInt(String path);

    /**
     * @param path
     *            path expression
     * @return the 64-bit long value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a long
     */
    long getLong(String path);

    /**
     * @param path
     *            path expression
     * @return the floating-point value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a double
     */
    double getDouble(String path);

    /**
     * @param path
     *            path expression
     * @return the string value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a string
     */
    String getString(String path);

    /**
     * @param path
     *            path expression
     * @return the {@link ConfigObject} value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to an object
     */
    ConfigObject getObject(String path);

    /**
     * @param path
     *            path expression
     * @return the nested {@code Config} value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to a Config
     */
    Config getConfig(String path);

    /**
     * Gets the value at the path as an unwrapped Java boxed value (
     * {@link java.lang.Boolean Boolean}, {@link java.lang.Integer Integer}, and
     * so on - see {@link ConfigValue#unwrapped()}).
     *
     * @param path
     *            path expression
     * @return the unwrapped value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     */
    Object getAnyRef(String path);

    /**
     * Gets the value at the given path, unless the value is a
     * null value or missing, in which case it throws just like
     * the other getters. Use {@code get()} on the {@link
     * Config#root()} object (or other object in the tree) if you
     * want an unprocessed value.
     *
     * @param path
     *            path expression
     * @return the value at the requested path
     * @throws ConfigException.Missing
     *             if value is absent or null
     */
    ConfigValue getValue(String path);

    /**
     * Gets a value as a size in bytes (parses special strings like "128M"). If
     * the value is already a number, then it's left alone; if it's a string,
     * it's parsed understanding unit suffixes such as "128K", as documented in
     * the <a
     * href="https://github.com/typesafehub/config/blob/master/HOCON.md">the
     * spec</a>.
     *
     * @param path
     *            path expression
     * @return the value at the requested path, in bytes
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to Long or String
     * @throws ConfigException.BadValue
     *             if value cannot be parsed as a size in bytes
     */
    Long getBytes(String path);

    /**
     * Get value as a duration in milliseconds. If the value is already a
     * number, then it's left alone; if it's a string, it's parsed understanding
     * units suffixes like "10m" or "5ns" as documented in the <a
     * href="https://github.com/typesafehub/config/blob/master/HOCON.md">the
     * spec</a>.
     *
     * @param path
     *            path expression
     * @return the duration value at the requested path, in milliseconds
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
     * string, it's parsed understanding unit suffixes, as for
     * {@link #getMilliseconds(String)}.
     *
     * @param path
     *            path expression
     * @return the duration value at the requested path, in nanoseconds
     * @throws ConfigException.Missing
     *             if value is absent or null
     * @throws ConfigException.WrongType
     *             if value is not convertible to Long or String
     * @throws ConfigException.BadValue
     *             if value cannot be parsed as a number of nanoseconds
     */
    Long getNanoseconds(String path);

    /**
     * Gets a list value (with any element type) as a {@link ConfigList}, which
     * implements {@code java.util.List<ConfigValue>}. Throws if the path is
     * unset or null.
     *
     * @param path
     *            the path to the list value.
     * @return the {@link ConfigList} at the path
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

    List<Long> getBytesList(String path);

    List<Long> getMillisecondsList(String path);

    List<Long> getNanosecondsList(String path);

    /**
     * Clone the config with only the given path (and its children) retained;
     * all sibling paths are removed.
     *
     * @param path
     *            path to keep
     * @return a copy of the config minus all paths except the one specified
     */
    Config withOnlyPath(String path);

    /**
     * Clone the config with the given path removed.
     *
     * @param path
     *            path to remove
     * @return a copy of the config minus the specified path
     */
    Config withoutPath(String path);
}
