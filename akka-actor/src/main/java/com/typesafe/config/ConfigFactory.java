/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

import java.io.File;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Properties;

import com.typesafe.config.impl.ConfigImpl;
import com.typesafe.config.impl.Parseable;

/**
 * Contains static methods for creating {@link Config} instances.
 *
 * <p>
 * See also {@link ConfigValueFactory} which contains static methods for
 * converting Java values into a {@link ConfigObject}. You can then convert a
 * {@code ConfigObject} into a {@code Config} with {@link ConfigObject#toConfig}.
 *
 * <p>
 * The static methods with "load" in the name do some sort of higher-level
 * operation potentially parsing multiple resources and resolving substitutions,
 * while the ones with "parse" in the name just create a {@link ConfigValue}
 * from a resource and nothing else.
 */
public final class ConfigFactory {
    private ConfigFactory() {
    }

    /**
     * Loads an application's configuration from the given classpath resource or
     * classpath resource basename, sandwiches it between default reference
     * config and default overrides, and then resolves it. The classpath
     * resource is "raw" (it should have no "/" prefix, and is not made relative
     * to any package, so it's like {@link ClassLoader#getResource} not
     * {@link Class#getResource}).
     *
     * <p>
     * Resources are loaded from the current thread's
     * {@link Thread#getContextClassLoader()}. In general, a library needs its
     * configuration to come from the class loader used to load that library, so
     * the proper "reference.conf" are present.
     *
     * <p>
     * The loaded object will already be resolved (substitutions have already
     * been processed). As a result, if you add more fallbacks then they won't
     * be seen by substitutions. Substitutions are the "${foo.bar}" syntax. If
     * you want to parse additional files or something then you need to use
     * {@link #load(Config)}.
     *
     * @param resourceBasename
     *            name (optionally without extension) of a resource on classpath
     * @return configuration for an application relative to context class loader
     */
    public static Config load(String resourceBasename) {
        return load(Thread.currentThread().getContextClassLoader(), resourceBasename);
    }

    /**
     * Like {@link #load(String)} but uses the supplied class loader instead of
     * the current thread's context class loader.
     *
     * @param loader
     * @param resourceBasename
     * @return configuration for an application relative to given class loader
     */
    public static Config load(ClassLoader loader, String resourceBasename) {
        return load(loader, resourceBasename, ConfigParseOptions.defaults(),
                ConfigResolveOptions.defaults());
    }

    /**
     * Like {@link #load(String)} but allows you to specify parse and resolve
     * options.
     *
     * @param resourceBasename
     *            the classpath resource name with optional extension
     * @param parseOptions
     *            options to use when parsing the resource
     * @param resolveOptions
     *            options to use when resolving the stack
     * @return configuration for an application
     */
    public static Config load(String resourceBasename, ConfigParseOptions parseOptions,
            ConfigResolveOptions resolveOptions) {
        return load(Thread.currentThread().getContextClassLoader(), resourceBasename, parseOptions,
                resolveOptions);
    }

    /**
     * Like {@link #load(String,ConfigParseOptions,ConfigResolveOptions)} but
     * allows you to specify a class loader
     *
     * @param loader
     *            class loader in which to find resources
     * @param resourceBasename
     *            the classpath resource name with optional extension
     * @param parseOptions
     *            options to use when parsing the resource
     * @param resolveOptions
     *            options to use when resolving the stack
     * @return configuration for an application
     */
    public static Config load(ClassLoader loader, String resourceBasename,
            ConfigParseOptions parseOptions, ConfigResolveOptions resolveOptions) {
        Config appConfig = ConfigFactory.parseResourcesAnySyntax(loader, resourceBasename,
                parseOptions);
        return load(loader, appConfig, resolveOptions);
    }

    /**
     * Assembles a standard configuration using a custom <code>Config</code>
     * object rather than loading "application.conf". The <code>Config</code>
     * object will be sandwiched between the default reference config and
     * default overrides and then resolved.
     *
     * @param config
     *            the application's portion of the configuration
     * @return resolved configuration with overrides and fallbacks added
     */
    public static Config load(Config config) {
        return load(Thread.currentThread().getContextClassLoader(), config);
    }

    public static Config load(ClassLoader loader, Config config) {
        return load(loader, config, ConfigResolveOptions.defaults());
    }

    /**
     * Like {@link #load(Config)} but allows you to specify
     * {@link ConfigResolveOptions}.
     *
     * @param config
     *            the application's portion of the configuration
     * @param resolveOptions
     *            options for resolving the assembled config stack
     * @return resolved configuration with overrides and fallbacks added
     */
    public static Config load(Config config, ConfigResolveOptions resolveOptions) {
        return load(Thread.currentThread().getContextClassLoader(), config, resolveOptions);
    }

    /**
     * Like {@link #load(Config,ConfigResolveOptions)} but allows you to specify
     * a class loader other than the context class loader.
     *
     * @param loader
     *            class loader to use when looking up override and reference
     *            configs
     * @param config
     *            the application's portion of the configuration
     * @param resolveOptions
     *            options for resolving the assembled config stack
     * @return resolved configuration with overrides and fallbacks added
     */
    public static Config load(ClassLoader loader, Config config, ConfigResolveOptions resolveOptions) {
        return defaultOverrides(loader).withFallback(config).withFallback(defaultReference(loader))
                .resolve(resolveOptions);
    }

    private static Config loadDefaultConfig(ClassLoader loader) {
        int specified = 0;

        // override application.conf with config.file, config.resource,
        // config.url if requested.
        String resource = System.getProperty("config.resource");
        if (resource != null)
            specified += 1;
        String file = System.getProperty("config.file");
        if (file != null)
            specified += 1;
        String url = System.getProperty("config.url");
        if (url != null)
            specified += 1;

        if (specified == 0) {
            return load(loader, "application");
        } else if (specified > 1) {
            throw new ConfigException.Generic("You set more than one of config.file='" + file
                    + "', config.url='" + url + "', config.resource='" + resource
                    + "'; don't know which one to use!");
        } else {
            if (resource != null) {
                // this deliberately does not parseResourcesAnySyntax; if
                // people want that they can use an include statement.
                return load(loader, parseResources(loader, resource));
            } else if (file != null) {
                return load(loader, parseFile(new File(file)));
            } else {
                try {
                    return load(loader, parseURL(new URL(url)));
                } catch (MalformedURLException e) {
                    throw new ConfigException.Generic("Bad URL in config.url system property: '"
                            + url + "': " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Loads a default configuration, equivalent to {@link #load(String)
     * load("application")} in most cases. This configuration should be used by
     * libraries and frameworks unless an application provides a different one.
     * <p>
     * This method may return a cached singleton.
     * <p>
     * If the system properties <code>config.resource</code>,
     * <code>config.file</code>, or <code>config.url</code> are set, then the
     * classpath resource, file, or URL specified in those properties will be
     * used rather than the default
     * <code>application.{conf,json,properties}</code> classpath resources.
     * These system properties should not be set in code (after all, you can
     * just parse whatever you want manually and then use {@link #load(Config)}
     * if you don't want to use <code>application.conf</code>). The properties
     * are intended for use by the person or script launching the application.
     * For example someone might have a <code>production.conf</code> that
     * include <code>application.conf</code> but then change a couple of values.
     * When launching the app they could specify
     * <code>-Dconfig.resource=production.conf</code> to get production mode.
     * <p>
     * If no system properties are set to change the location of the default
     * configuration, <code>ConfigFactory.load()</code> is equivalent to
     * <code>ConfigFactory.load("application")</code>.
     *
     * @return configuration for an application
     */
    public static Config load() {
        return load(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Like {@link #load()} but allows specifying a class loader other than the
     * thread's current context class loader.
     *
     * @param loader
     *            class loader for finding resources
     * @return configuration for an application
     */
    public static Config load(ClassLoader loader) {
        return loadDefaultConfig(loader);
    }

    /**
     * Obtains the default reference configuration, which is currently created
     * by merging all resources "reference.conf" found on the classpath and
     * overriding the result with system properties. The returned reference
     * configuration will already have substitutions resolved.
     *
     * <p>
     * Libraries and frameworks should ship with a "reference.conf" in their
     * jar.
     *
     * <p>
     * The reference config must be looked up in the class loader that contains
     * the libraries that you want to use with this config, so the
     * "reference.conf" for each library can be found. Use
     * {@link #defaultReference(ClassLoader)} if the context class loader is not
     * suitable.
     *
     * <p>
     * The {@link #load()} methods merge this configuration for you
     * automatically.
     *
     * <p>
     * Future versions may look for reference configuration in more places. It
     * is not guaranteed that this method <em>only</em> looks at
     * "reference.conf".
     *
     * @return the default reference config for context class loader
     */
    public static Config defaultReference() {
        return defaultReference(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Like {@link #defaultReference()} but allows you to specify a class loader
     * to use rather than the current context class loader.
     *
     * @param loader
     * @return the default reference config for this class loader
     */
    public static Config defaultReference(ClassLoader loader) {
        return ConfigImpl.defaultReference(loader);
    }

    /**
     * Obtains the default override configuration, which currently consists of
     * system properties. The returned override configuration will already have
     * substitutions resolved.
     *
     * <p>
     * The {@link #load()} methods merge this configuration for you
     * automatically.
     *
     * <p>
     * Future versions may get overrides in more places. It is not guaranteed
     * that this method <em>only</em> uses system properties.
     *
     * @return the default override configuration
     */
    public static Config defaultOverrides() {
        return systemProperties();
    }

    /**
     * Like {@link #defaultOverrides()} but allows you to specify a class loader
     * to use rather than the current context class loader.
     *
     * @param loader
     * @return the default override configuration
     */
    public static Config defaultOverrides(ClassLoader loader) {
        return systemProperties();
    }

    /**
     * Gets an empty configuration. See also {@link #empty(String)} to create an
     * empty configuration with a description, which may improve user-visible
     * error messages.
     *
     * @return an empty configuration
     */
    public static Config empty() {
        return empty(null);
    }

    /**
     * Gets an empty configuration with a description to be used to create a
     * {@link ConfigOrigin} for this <code>Config</code>. The description should
     * be very short and say what the configuration is, like "default settings"
     * or "foo settings" or something. (Presumably you will merge some actual
     * settings into this empty config using {@link Config#withFallback}, making
     * the description more useful.)
     *
     * @param originDescription
     *            description of the config
     * @return an empty configuration
     */
    public static Config empty(String originDescription) {
        return ConfigImpl.emptyConfig(originDescription);
    }

    /**
     * Gets a <code>Config</code> containing the system properties from
     * {@link java.lang.System#getProperties()}, parsed and converted as with
     * {@link #parseProperties}. This method can return a global immutable
     * singleton, so it's preferred over parsing system properties yourself.
     *
     * <p>
     * {@link #load} will include the system properties as overrides already, as
     * will {@link #defaultReference} and {@link #defaultOverrides}.
     *
     * <p>
     * Because this returns a singleton, it will not notice changes to system
     * properties made after the first time this method is called.
     *
     * @return system properties parsed into a <code>Config</code>
     */
    public static Config systemProperties() {
        return ConfigImpl.systemPropertiesAsConfig();
    }

    /**
     * Gets a <code>Config</code> containing the system's environment variables.
     * This method can return a global immutable singleton.
     *
     * <p>
     * Environment variables are used as fallbacks when resolving substitutions
     * whether or not this object is included in the config being resolved, so
     * you probably don't need to use this method for most purposes. It can be a
     * nicer API for accessing environment variables than raw
     * {@link java.lang.System#getenv(String)} though, since you can use methods
     * such as {@link Config#getInt}.
     *
     * @return system environment variables parsed into a <code>Config</code>
     */
    public static Config systemEnvironment() {
        return ConfigImpl.envVariablesAsConfig();
    }

    /**
     * Converts a Java {@link java.util.Properties} object to a
     * {@link ConfigObject} using the rules documented in the <a
     * href="https://github.com/typesafehub/config/blob/master/HOCON.md">HOCON
     * spec</a>. The keys in the <code>Properties</code> object are split on the
     * period character '.' and treated as paths. The values will all end up as
     * string values. If you have both "a=foo" and "a.b=bar" in your properties
     * file, so "a" is both the object containing "b" and the string "foo", then
     * the string value is dropped.
     *
     * <p>
     * If you want to have <code>System.getProperties()</code> as a
     * ConfigObject, it's better to use the {@link #systemProperties()} method
     * which returns a cached global singleton.
     *
     * @param properties
     *            a Java Properties object
     * @param options
     * @return the parsed configuration
     */
    public static Config parseProperties(Properties properties,
            ConfigParseOptions options) {
        return Parseable.newProperties(properties, options).parse().toConfig();
    }

    public static Config parseProperties(Properties properties) {
        return parseProperties(properties, ConfigParseOptions.defaults());
    }

    public static Config parseReader(Reader reader, ConfigParseOptions options) {
        return Parseable.newReader(reader, options).parse().toConfig();
    }

    public static Config parseReader(Reader reader) {
        return parseReader(reader, ConfigParseOptions.defaults());
    }

    public static Config parseURL(URL url, ConfigParseOptions options) {
        return Parseable.newURL(url, options).parse().toConfig();
    }

    public static Config parseURL(URL url) {
        return parseURL(url, ConfigParseOptions.defaults());
    }

    public static Config parseFile(File file, ConfigParseOptions options) {
        return Parseable.newFile(file, options).parse().toConfig();
    }

    public static Config parseFile(File file) {
        return parseFile(file, ConfigParseOptions.defaults());
    }

    /**
     * Parses a file with a flexible extension. If the <code>fileBasename</code>
     * already ends in a known extension, this method parses it according to
     * that extension (the file's syntax must match its extension). If the
     * <code>fileBasename</code> does not end in an extension, it parses files
     * with all known extensions and merges whatever is found.
     *
     * <p>
     * In the current implementation, the extension ".conf" forces
     * {@link ConfigSyntax#CONF}, ".json" forces {@link ConfigSyntax#JSON}, and
     * ".properties" forces {@link ConfigSyntax#PROPERTIES}. When merging files,
     * ".conf" falls back to ".json" falls back to ".properties".
     *
     * <p>
     * Future versions of the implementation may add additional syntaxes or
     * additional extensions. However, the ordering (fallback priority) of the
     * three current extensions will remain the same.
     *
     * <p>
     * If <code>options</code> forces a specific syntax, this method only parses
     * files with an extension matching that syntax.
     *
     * <p>
     * If {@link ConfigParseOptions#getAllowMissing options.getAllowMissing()}
     * is true, then no files have to exist; if false, then at least one file
     * has to exist.
     *
     * @param fileBasename
     *            a filename with or without extension
     * @param options
     *            parse options
     * @return the parsed configuration
     */
    public static Config parseFileAnySyntax(File fileBasename,
            ConfigParseOptions options) {
        return ConfigImpl.parseFileAnySyntax(fileBasename, options).toConfig();
    }

    public static Config parseFileAnySyntax(File fileBasename) {
        return parseFileAnySyntax(fileBasename, ConfigParseOptions.defaults());
    }

    /**
     * Parses all resources on the classpath with the given name and merges them
     * into a single <code>Config</code>.
     *
     * <p>
     * If the resource name does not begin with a "/", it will have the supplied
     * class's package added to it, in the same way as
     * {@link java.lang.Class#getResource}.
     *
     * <p>
     * Duplicate resources with the same name are merged such that ones returned
     * earlier from {@link ClassLoader#getResources} fall back to (have higher
     * priority than) the ones returned later. This implies that resources
     * earlier in the classpath override those later in the classpath when they
     * configure the same setting. However, in practice real applications may
     * not be consistent about classpath ordering, so be careful. It may be best
     * to avoid assuming too much.
     *
     * @param klass
     *            <code>klass.getClassLoader()</code> will be used to load
     *            resources, and non-absolute resource names will have this
     *            class's package added
     * @param resource
     *            resource to look up, relative to <code>klass</code>'s package
     *            or absolute starting with a "/"
     * @param options
     *            parse options
     * @return the parsed configuration
     */
    public static Config parseResources(Class<?> klass, String resource,
            ConfigParseOptions options) {
        return Parseable.newResources(klass, resource, options).parse()
                .toConfig();
    }

    public static Config parseResources(Class<?> klass, String resource) {
        return parseResources(klass, resource, ConfigParseOptions.defaults());
    }

    /**
     * Parses classpath resources with a flexible extension. In general, this
     * method has the same behavior as
     * {@link #parseFileAnySyntax(File,ConfigParseOptions)} but for classpath
     * resources instead, as in {@link #parseResources}.
     *
     * <p>
     * There is a thorny problem with this method, which is that
     * {@link java.lang.ClassLoader#getResources} must be called separately for
     * each possible extension. The implementation ends up with separate lists
     * of resources called "basename.conf" and "basename.json" for example. As a
     * result, the ideal ordering between two files with different extensions is
     * unknown; there is no way to figure out how to merge the two lists in
     * classpath order. To keep it simple, the lists are simply concatenated,
     * with the same syntax priorities as
     * {@link #parseFileAnySyntax(File,ConfigParseOptions) parseFileAnySyntax()}
     * - all ".conf" resources are ahead of all ".json" resources which are
     * ahead of all ".properties" resources.
     *
     * @param klass
     *            class which determines the <code>ClassLoader</code> and the
     *            package for relative resource names
     * @param resourceBasename
     *            a resource name as in {@link java.lang.Class#getResource},
     *            with or without extension
     * @param options
     *            parse options
     * @return the parsed configuration
     */
    public static Config parseResourcesAnySyntax(Class<?> klass, String resourceBasename,
            ConfigParseOptions options) {
        return ConfigImpl.parseResourcesAnySyntax(klass, resourceBasename,
                options).toConfig();
    }

    public static Config parseResourcesAnySyntax(Class<?> klass, String resourceBasename) {
        return parseResourcesAnySyntax(klass, resourceBasename, ConfigParseOptions.defaults());
    }

    /**
     * Parses all resources on the classpath with the given name and merges them
     * into a single <code>Config</code>.
     *
     * <p>
     * This works like {@link java.lang.ClassLoader#getResource}, not like
     * {@link java.lang.Class#getResource}, so the name never begins with a
     * slash.
     *
     * <p>
     * See {@link #parseResources(Class,String,ConfigParseOptions)} for full
     * details.
     *
     * @param loader
     *            will be used to load resources
     * @param resource
     *            resource to look up
     * @param options
     *            parse options
     * @return the parsed configuration
     */
    public static Config parseResources(ClassLoader loader, String resource,
            ConfigParseOptions options) {
        return Parseable.newResources(loader, resource, options).parse().toConfig();
    }

    public static Config parseResources(ClassLoader loader, String resource) {
        return parseResources(loader, resource, ConfigParseOptions.defaults());
    }

    /**
     * Parses classpath resources with a flexible extension. In general, this
     * method has the same behavior as
     * {@link #parseFileAnySyntax(File,ConfigParseOptions)} but for classpath
     * resources instead, as in
     * {@link #parseResources(ClassLoader,String,ConfigParseOptions)}.
     *
     * <p>
     * {@link #parseResourcesAnySyntax(Class,String,ConfigParseOptions)} differs
     * in the syntax for the resource name, but otherwise see
     * {@link #parseResourcesAnySyntax(Class,String,ConfigParseOptions)} for
     * some details and caveats on this method.
     *
     * @param loader
     *            class loader to look up resources in
     * @param resourceBasename
     *            a resource name as in
     *            {@link java.lang.ClassLoader#getResource}, with or without
     *            extension
     * @param options
     *            parse options
     * @return the parsed configuration
     */
    public static Config parseResourcesAnySyntax(ClassLoader loader, String resourceBasename,
            ConfigParseOptions options) {
        return ConfigImpl.parseResourcesAnySyntax(loader, resourceBasename, options).toConfig();
    }

    public static Config parseResourcesAnySyntax(ClassLoader loader, String resourceBasename) {
        return parseResourcesAnySyntax(loader, resourceBasename, ConfigParseOptions.defaults());
    }

    /**
     * Like {@link #parseResources(ClassLoader,String,ConfigParseOptions)} but
     * uses thread's current context class loader.
     */
    public static Config parseResources(String resource, ConfigParseOptions options) {
        return Parseable
                .newResources(Thread.currentThread().getContextClassLoader(), resource, options)
                .parse().toConfig();
    }

    /**
     * Like {@link #parseResources(ClassLoader,String)} but uses thread's
     * current context class loader.
     */
    public static Config parseResources(String resource) {
        return parseResources(Thread.currentThread().getContextClassLoader(), resource,
                ConfigParseOptions.defaults());
    }

    /**
     * Like
     * {@link #parseResourcesAnySyntax(ClassLoader,String,ConfigParseOptions)}
     * but uses thread's current context class loader.
     */
    public static Config parseResourcesAnySyntax(String resourceBasename, ConfigParseOptions options) {
        return ConfigImpl.parseResourcesAnySyntax(Thread.currentThread().getContextClassLoader(),
                resourceBasename, options).toConfig();
    }

    /**
     * Like {@link #parseResourcesAnySyntax(ClassLoader,String)} but uses
     * thread's current context class loader.
     */
    public static Config parseResourcesAnySyntax(String resourceBasename) {
        return parseResourcesAnySyntax(Thread.currentThread().getContextClassLoader(),
                resourceBasename, ConfigParseOptions.defaults());
    }

    public static Config parseString(String s, ConfigParseOptions options) {
        return Parseable.newString(s, options).parse().toConfig();
    }

    public static Config parseString(String s) {
        return parseString(s, ConfigParseOptions.defaults());
    }

    /**
     * Creates a {@code Config} based on a {@link java.util.Map} from paths to
     * plain Java values. Similar to
     * {@link ConfigValueFactory#fromMap(Map,String)}, except the keys in the
     * map are path expressions, rather than keys; and correspondingly it
     * returns a {@code Config} instead of a {@code ConfigObject}. This is more
     * convenient if you are writing literal maps in code, and less convenient
     * if you are getting your maps from some data source such as a parser.
     *
     * <p>
     * An exception will be thrown (and it is a bug in the caller of the method)
     * if a path is both an object and a value, for example if you had both
     * "a=foo" and "a.b=bar", then "a" is both the string "foo" and the parent
     * object of "b". The caller of this method should ensure that doesn't
     * happen.
     *
     * @param values
     * @param originDescription
     *            description of what this map represents, like a filename, or
     *            "default settings" (origin description is used in error
     *            messages)
     * @return the map converted to a {@code Config}
     */
    public static Config parseMap(Map<String, ? extends Object> values,
            String originDescription) {
        return ConfigImpl.fromPathMap(values, originDescription).toConfig();
    }

    /**
     * See the other overload of {@link #parseMap(Map, String)} for details,
     * this one just uses a default origin description.
     *
     * @param values
     * @return the map converted to a {@code Config}
     */
    public static Config parseMap(Map<String, ? extends Object> values) {
        return parseMap(values, null);
    }
}
