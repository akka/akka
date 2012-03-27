/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigParseable;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValue;

/** This is public but is only supposed to be used by the "config" package */
public class ConfigImpl {

    private interface NameSource {
        ConfigParseable nameToParseable(String name);
    }

    // this function is a little tricky because there are three places we're
    // trying to use it; for 'include "basename"' in a .conf file, for
    // loading app.{conf,json,properties} from classpath, and for
    // loading app.{conf,json,properties} from the filesystem.
    private static ConfigObject fromBasename(NameSource source, String name,
            ConfigParseOptions options) {
        ConfigObject obj;
        if (name.endsWith(".conf") || name.endsWith(".json")
                || name.endsWith(".properties")) {
            ConfigParseable p = source.nameToParseable(name);

            obj = p.parse(p.options().setAllowMissing(options.getAllowMissing()));
        } else {
            ConfigParseable confHandle = source.nameToParseable(name + ".conf");
            ConfigParseable jsonHandle = source.nameToParseable(name + ".json");
            ConfigParseable propsHandle = source.nameToParseable(name
                    + ".properties");
            boolean gotSomething = false;
            List<String> failMessages = new ArrayList<String>();

            ConfigSyntax syntax = options.getSyntax();

            obj = SimpleConfigObject.empty(SimpleConfigOrigin.newSimple(name));
            if (syntax == null || syntax == ConfigSyntax.CONF) {
                try {
                    obj = confHandle.parse(confHandle.options().setAllowMissing(false)
                            .setSyntax(ConfigSyntax.CONF));
                    gotSomething = true;
                } catch (ConfigException.IO e) {
                    failMessages.add(e.getMessage());
                }
            }

            if (syntax == null || syntax == ConfigSyntax.JSON) {
                try {
                    ConfigObject parsed = jsonHandle.parse(jsonHandle.options()
                            .setAllowMissing(false).setSyntax(ConfigSyntax.JSON));
                    obj = obj.withFallback(parsed);
                    gotSomething = true;
                } catch (ConfigException.IO e) {
                    failMessages.add(e.getMessage());
                }
            }

            if (syntax == null || syntax == ConfigSyntax.PROPERTIES) {
                try {
                    ConfigObject parsed = propsHandle.parse(propsHandle.options()
                            .setAllowMissing(false).setSyntax(ConfigSyntax.PROPERTIES));
                    obj = obj.withFallback(parsed);
                    gotSomething = true;
                } catch (ConfigException.IO e) {
                    failMessages.add(e.getMessage());
                }
            }

            if (!options.getAllowMissing() && !gotSomething) {
                String failMessage;
                if (failMessages.isEmpty()) {
                    // this should not happen
                    throw new ConfigException.BugOrBroken(
                            "should not be reached: nothing found but no exceptions thrown");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String msg : failMessages) {
                        sb.append(msg);
                        sb.append(", ");
                    }
                    sb.setLength(sb.length() - 2);
                    failMessage = sb.toString();
                }
                throw new ConfigException.IO(SimpleConfigOrigin.newSimple(name), failMessage);
            }
        }

        return obj;
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static ConfigObject parseResourcesAnySyntax(final Class<?> klass,
            String resourceBasename, final ConfigParseOptions baseOptions) {
        NameSource source = new NameSource() {
            @Override
            public ConfigParseable nameToParseable(String name) {
                return Parseable.newResources(klass, name, baseOptions);
            }
        };
        return fromBasename(source, resourceBasename, baseOptions);
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static ConfigObject parseResourcesAnySyntax(final ClassLoader loader,
            String resourceBasename, final ConfigParseOptions baseOptions) {
        NameSource source = new NameSource() {
            @Override
            public ConfigParseable nameToParseable(String name) {
                return Parseable.newResources(loader, name, baseOptions);
            }
        };
        return fromBasename(source, resourceBasename, baseOptions);
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static ConfigObject parseFileAnySyntax(final File basename,
            final ConfigParseOptions baseOptions) {
        NameSource source = new NameSource() {
            @Override
            public ConfigParseable nameToParseable(String name) {
                return Parseable.newFile(new File(name), baseOptions);
            }
        };
        return fromBasename(source, basename.getPath(), baseOptions);
    }

    static AbstractConfigObject emptyObject(String originDescription) {
        ConfigOrigin origin = originDescription != null ? SimpleConfigOrigin
                .newSimple(originDescription) : null;
        return emptyObject(origin);
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static Config emptyConfig(String originDescription) {
        return emptyObject(originDescription).toConfig();
    }

    static AbstractConfigObject empty(ConfigOrigin origin) {
        return emptyObject(origin);
    }

    // default origin for values created with fromAnyRef and no origin specified
    final private static ConfigOrigin defaultValueOrigin = SimpleConfigOrigin
            .newSimple("hardcoded value");
    final private static ConfigBoolean defaultTrueValue = new ConfigBoolean(
            defaultValueOrigin, true);
    final private static ConfigBoolean defaultFalseValue = new ConfigBoolean(
            defaultValueOrigin, false);
    final private static ConfigNull defaultNullValue = new ConfigNull(
            defaultValueOrigin);
    final private static SimpleConfigList defaultEmptyList = new SimpleConfigList(
            defaultValueOrigin, Collections.<AbstractConfigValue> emptyList());
    final private static SimpleConfigObject defaultEmptyObject = SimpleConfigObject
            .empty(defaultValueOrigin);

    private static SimpleConfigList emptyList(ConfigOrigin origin) {
        if (origin == null || origin == defaultValueOrigin)
            return defaultEmptyList;
        else
            return new SimpleConfigList(origin,
                    Collections.<AbstractConfigValue> emptyList());
    }

    private static AbstractConfigObject emptyObject(ConfigOrigin origin) {
        // we want null origin to go to SimpleConfigObject.empty() to get the
        // origin "empty config" rather than "hardcoded value"
        if (origin == defaultValueOrigin)
            return defaultEmptyObject;
        else
            return SimpleConfigObject.empty(origin);
    }

    private static ConfigOrigin valueOrigin(String originDescription) {
        if (originDescription == null)
            return defaultValueOrigin;
        else
            return SimpleConfigOrigin.newSimple(originDescription);
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static ConfigValue fromAnyRef(Object object, String originDescription) {
        ConfigOrigin origin = valueOrigin(originDescription);
        return fromAnyRef(object, origin, FromMapMode.KEYS_ARE_KEYS);
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static ConfigObject fromPathMap(
            Map<String, ? extends Object> pathMap, String originDescription) {
        ConfigOrigin origin = valueOrigin(originDescription);
        return (ConfigObject) fromAnyRef(pathMap, origin,
                FromMapMode.KEYS_ARE_PATHS);
    }

    static AbstractConfigValue fromAnyRef(Object object, ConfigOrigin origin,
            FromMapMode mapMode) {
        if (origin == null)
            throw new ConfigException.BugOrBroken(
                    "origin not supposed to be null");

        if (object == null) {
            if (origin != defaultValueOrigin)
                return new ConfigNull(origin);
            else
                return defaultNullValue;
        } else if (object instanceof Boolean) {
            if (origin != defaultValueOrigin) {
                return new ConfigBoolean(origin, (Boolean) object);
            } else if ((Boolean) object) {
                return defaultTrueValue;
            } else {
                return defaultFalseValue;
            }
        } else if (object instanceof String) {
            return new ConfigString(origin, (String) object);
        } else if (object instanceof Number) {
            // here we always keep the same type that was passed to us,
            // rather than figuring out if a Long would fit in an Int
            // or a Double has no fractional part. i.e. deliberately
            // not using ConfigNumber.newNumber() when we have a
            // Double, Integer, or Long.
            if (object instanceof Double) {
                return new ConfigDouble(origin, (Double) object, null);
            } else if (object instanceof Integer) {
                return new ConfigInt(origin, (Integer) object, null);
            } else if (object instanceof Long) {
                return new ConfigLong(origin, (Long) object, null);
            } else {
                return ConfigNumber.newNumber(origin,
                        ((Number) object).doubleValue(), null);
            }
        } else if (object instanceof Map) {
            if (((Map<?, ?>) object).isEmpty())
                return emptyObject(origin);

            if (mapMode == FromMapMode.KEYS_ARE_KEYS) {
                Map<String, AbstractConfigValue> values = new HashMap<String, AbstractConfigValue>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                    Object key = entry.getKey();
                    if (!(key instanceof String))
                        throw new ConfigException.BugOrBroken(
                                "bug in method caller: not valid to create ConfigObject from map with non-String key: "
                                        + key);
                    AbstractConfigValue value = fromAnyRef(entry.getValue(),
                            origin, mapMode);
                    values.put((String) key, value);
                }

                return new SimpleConfigObject(origin, values);
            } else {
                return PropertiesParser.fromPathMap(origin, (Map<?, ?>) object);
            }
        } else if (object instanceof Iterable) {
            Iterator<?> i = ((Iterable<?>) object).iterator();
            if (!i.hasNext())
                return emptyList(origin);

            List<AbstractConfigValue> values = new ArrayList<AbstractConfigValue>();
            while (i.hasNext()) {
                AbstractConfigValue v = fromAnyRef(i.next(), origin, mapMode);
                values.add(v);
            }

            return new SimpleConfigList(origin, values);
        } else {
            throw new ConfigException.BugOrBroken(
                    "bug in method caller: not valid to create ConfigValue from: "
                            + object);
        }
    }

    private static class SimpleIncluder implements ConfigIncluder {

        private ConfigIncluder fallback;

        SimpleIncluder(ConfigIncluder fallback) {
            this.fallback = fallback;
        }

        @Override
        public ConfigObject include(final ConfigIncludeContext context,
                String name) {
            NameSource source = new NameSource() {
                @Override
                public ConfigParseable nameToParseable(String name) {
                    ConfigParseable p = context.relativeTo(name);
                    if (p == null) {
                        // avoid returning null
                        return Parseable.newNotFound(name, "include was not found: '" + name + "'",
                                ConfigParseOptions.defaults());
                    } else {
                        return p;
                    }
                }
            };

            ConfigObject obj = fromBasename(source, name, ConfigParseOptions
                    .defaults().setAllowMissing(true));

            // now use the fallback includer if any and merge
            // its result.
            if (fallback != null) {
                return obj.withFallback(fallback.include(context, name));
            } else {
                return obj;
            }
        }

        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            if (this == fallback) {
                throw new ConfigException.BugOrBroken(
                        "trying to create includer cycle");
            } else if (this.fallback == fallback) {
                return this;
            } else if (this.fallback != null) {
                return new SimpleIncluder(this.fallback.withFallback(fallback));
            } else {
                return new SimpleIncluder(fallback);
            }
        }
    }

    private static class DefaultIncluderHolder {
        static final ConfigIncluder defaultIncluder = new SimpleIncluder(null);
    }

    static ConfigIncluder defaultIncluder() {
        try {
            return DefaultIncluderHolder.defaultIncluder;
        } catch (ExceptionInInitializerError e) {
            throw ConfigImplUtil.extractInitializerError(e);
        }
    }

    private static AbstractConfigObject loadSystemProperties() {
        return (AbstractConfigObject) Parseable.newProperties(System.getProperties(),
                ConfigParseOptions.defaults().setOriginDescription("system properties")).parse();
    }

    private static class SystemPropertiesHolder {
        // this isn't final due to the reloadSystemPropertiesConfig() hack below
        static AbstractConfigObject systemProperties = loadSystemProperties();
    }

    static AbstractConfigObject systemPropertiesAsConfigObject() {
        try {
            return SystemPropertiesHolder.systemProperties;
        } catch (ExceptionInInitializerError e) {
            throw ConfigImplUtil.extractInitializerError(e);
        }
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static Config systemPropertiesAsConfig() {
        return systemPropertiesAsConfigObject().toConfig();
    }

    // this is a hack to let us set system props in the test suite.
    // obviously not thread-safe.
    static void reloadSystemPropertiesConfig() {
        SystemPropertiesHolder.systemProperties = loadSystemProperties();
    }

    private static AbstractConfigObject loadEnvVariables() {
        Map<String, String> env = System.getenv();
        Map<String, AbstractConfigValue> m = new HashMap<String, AbstractConfigValue>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            m.put(key,
                    new ConfigString(SimpleConfigOrigin.newSimple("env var " + key), entry
                            .getValue()));
        }
        return new SimpleConfigObject(SimpleConfigOrigin.newSimple("env variables"),
                m, ResolveStatus.RESOLVED, false /* ignoresFallbacks */);
    }

    private static class EnvVariablesHolder {
        static final AbstractConfigObject envVariables = loadEnvVariables();
    }

    static AbstractConfigObject envVariablesAsConfigObject() {
        try {
            return EnvVariablesHolder.envVariables;
        } catch (ExceptionInInitializerError e) {
            throw ConfigImplUtil.extractInitializerError(e);
        }
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static Config envVariablesAsConfig() {
        return envVariablesAsConfigObject().toConfig();
    }

    /** For use ONLY by library internals, DO NOT TOUCH not guaranteed ABI */
    public static Config defaultReference(ClassLoader loader) {
        Config unresolvedResources = Parseable
                .newResources(loader, "reference.conf", ConfigParseOptions.defaults()).parse()
                .toConfig();
        return systemPropertiesAsConfig().withFallback(unresolvedResources).resolve();
    }
}
