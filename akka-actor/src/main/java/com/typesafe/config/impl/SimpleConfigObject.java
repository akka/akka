/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValue;

final class SimpleConfigObject extends AbstractConfigObject {

    // this map should never be modified - assume immutable
    final private Map<String, AbstractConfigValue> value;
    final private boolean resolved;
    final private boolean ignoresFallbacks;

    SimpleConfigObject(ConfigOrigin origin,
            Map<String, AbstractConfigValue> value, ResolveStatus status,
            boolean ignoresFallbacks) {
        super(origin);
        if (value == null)
            throw new ConfigException.BugOrBroken(
                    "creating config object with null map");
        this.value = value;
        this.resolved = status == ResolveStatus.RESOLVED;
        this.ignoresFallbacks = ignoresFallbacks;
    }

    SimpleConfigObject(ConfigOrigin origin,
            Map<String, AbstractConfigValue> value) {
        this(origin, value, ResolveStatus.fromValues(value.values()), false /* ignoresFallbacks */);
    }

    @Override
    protected AbstractConfigValue peek(String key) {
        return value.get(key);
    }

    @Override
    protected SimpleConfigObject newCopy(ResolveStatus newStatus, boolean newIgnoresFallbacks,
            ConfigOrigin newOrigin) {
        return new SimpleConfigObject(newOrigin, value, newStatus, newIgnoresFallbacks);
    }

    @Override
    ResolveStatus resolveStatus() {
        return ResolveStatus.fromBoolean(resolved);
    }

    @Override
    protected boolean ignoresFallbacks() {
        return ignoresFallbacks;
    }

    @Override
    public Map<String, Object> unwrapped() {
        Map<String, Object> m = new HashMap<String, Object>();
        for (Map.Entry<String, AbstractConfigValue> e : value.entrySet()) {
            m.put(e.getKey(), e.getValue().unwrapped());
        }
        return m;
    }

    @Override
    public boolean containsKey(Object key) {
        return value.containsKey(key);
    }

    @Override
    public Set<String> keySet() {
        return value.keySet();
    }

    @Override
    public boolean containsValue(Object v) {
        return value.containsValue(v);
    }

    @Override
    public Set<Map.Entry<String, ConfigValue>> entrySet() {
        // total bloat just to work around lack of type variance

        HashSet<java.util.Map.Entry<String, ConfigValue>> entries = new HashSet<Map.Entry<String, ConfigValue>>();
        for (Map.Entry<String, AbstractConfigValue> e : value.entrySet()) {
            entries.add(new AbstractMap.SimpleImmutableEntry<String, ConfigValue>(
                    e.getKey(), e
                    .getValue()));
        }
        return entries;
    }

    @Override
    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public int size() {
        return value.size();
    }

    @Override
    public Collection<ConfigValue> values() {
        return new HashSet<ConfigValue>(value.values());
    }

    final private static String EMPTY_NAME = "empty config";
    final private static SimpleConfigObject emptyInstance = empty(SimpleConfigOrigin
            .newSimple(EMPTY_NAME));

    final static SimpleConfigObject empty() {
        return emptyInstance;
    }

    final static SimpleConfigObject empty(ConfigOrigin origin) {
        if (origin == null)
            return empty();
        else
            return new SimpleConfigObject(origin,
                    Collections.<String, AbstractConfigValue> emptyMap());
    }

    final static SimpleConfigObject emptyMissing(ConfigOrigin baseOrigin) {
        return new SimpleConfigObject(SimpleConfigOrigin.newSimple(
                baseOrigin.description() + " (not found)"),
                Collections.<String, AbstractConfigValue> emptyMap());
    }
}
