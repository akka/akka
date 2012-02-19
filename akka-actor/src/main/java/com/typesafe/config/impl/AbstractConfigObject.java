/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigMergeable;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

abstract class AbstractConfigObject extends AbstractConfigValue implements
        ConfigObject {

    private static final long serialVersionUID = 1L;

    final private SimpleConfig config;

    protected AbstractConfigObject(ConfigOrigin origin) {
        super(origin);
        this.config = new SimpleConfig(this);
    }

    @Override
    public SimpleConfig toConfig() {
        return config;
    }

    @Override
    public AbstractConfigObject toFallbackValue() {
        return this;
    }

    /**
     * This looks up the key with no transformation or type conversion of any
     * kind, and returns null if the key is not present.
     *
     * @param key
     * @return the unmodified raw value or null
     */
    protected abstract AbstractConfigValue peek(String key);

    protected AbstractConfigValue peek(String key,
            SubstitutionResolver resolver, int depth,
            ConfigResolveOptions options) {
        AbstractConfigValue v = peek(key);

        if (v != null && resolver != null) {
            v = resolver.resolve(v, depth, options);
        }

        return v;
    }

    /**
     * Looks up the path with no transformation, type conversion, or exceptions
     * (just returns null if path not found). Does however resolve the path, if
     * resolver != null.
     */
    protected AbstractConfigValue peekPath(Path path, SubstitutionResolver resolver,
            int depth, ConfigResolveOptions options) {
        return peekPath(this, path, resolver, depth, options);
    }

    AbstractConfigValue peekPath(Path path) {
        return peekPath(this, path, null, 0, null);
    }

    private static AbstractConfigValue peekPath(AbstractConfigObject self, Path path,
            SubstitutionResolver resolver, int depth,
            ConfigResolveOptions options) {
        String key = path.first();
        Path next = path.remainder();

        if (next == null) {
            AbstractConfigValue v = self.peek(key, resolver, depth, options);
            return v;
        } else {
            // it's important to ONLY resolve substitutions here, not
            // all values, because if you resolve arrays or objects
            // it creates unnecessary cycles as a side effect (any sibling
            // of the object we want to follow could cause a cycle, not just
            // the object we want to follow).

            ConfigValue v = self.peek(key);

            if (v instanceof ConfigSubstitution && resolver != null) {
                v = resolver.resolve((AbstractConfigValue) v, depth, options);
            }

            if (v instanceof AbstractConfigObject) {
                return peekPath((AbstractConfigObject) v, next, resolver,
                        depth, options);
            } else {
                return null;
            }
        }
    }

    @Override
    public ConfigValueType valueType() {
        return ConfigValueType.OBJECT;
    }

    protected abstract AbstractConfigObject newCopy(ResolveStatus status, boolean ignoresFallbacks,
            ConfigOrigin origin);

    @Override
    protected AbstractConfigObject newCopy(boolean ignoresFallbacks, ConfigOrigin origin) {
        return newCopy(resolveStatus(), ignoresFallbacks, origin);
    }

    @Override
    protected final AbstractConfigObject mergedWithTheUnmergeable(Unmergeable fallback) {
        if (ignoresFallbacks())
            throw new ConfigException.BugOrBroken("should not be reached");

        List<AbstractConfigValue> stack = new ArrayList<AbstractConfigValue>();
        if (this instanceof Unmergeable) {
            stack.addAll(((Unmergeable) this).unmergedValues());
        } else {
            stack.add(this);
        }
        stack.addAll(fallback.unmergedValues());
        return new ConfigDelayedMergeObject(mergeOrigins(stack), stack,
                ((AbstractConfigValue) fallback).ignoresFallbacks());
    }

    @Override
    protected AbstractConfigObject mergedWithObject(AbstractConfigObject fallback) {
        if (ignoresFallbacks())
            throw new ConfigException.BugOrBroken("should not be reached");

        boolean changed = false;
        boolean allResolved = true;
        Map<String, AbstractConfigValue> merged = new HashMap<String, AbstractConfigValue>();
        Set<String> allKeys = new HashSet<String>();
        allKeys.addAll(this.keySet());
        allKeys.addAll(fallback.keySet());
        for (String key : allKeys) {
            AbstractConfigValue first = this.peek(key);
            AbstractConfigValue second = fallback.peek(key);
            AbstractConfigValue kept;
            if (first == null)
                kept = second;
            else if (second == null)
                kept = first;
            else
                kept = first.withFallback(second);

            merged.put(key, kept);

            if (first != kept)
                changed = true;

            if (kept.resolveStatus() == ResolveStatus.UNRESOLVED)
                allResolved = false;
        }

        ResolveStatus newResolveStatus = ResolveStatus.fromBoolean(allResolved);
        boolean newIgnoresFallbacks = fallback.ignoresFallbacks();

        if (changed)
            return new SimpleConfigObject(mergeOrigins(this, fallback), merged, newResolveStatus,
                    newIgnoresFallbacks);
        else if (newResolveStatus != resolveStatus() || newIgnoresFallbacks != ignoresFallbacks())
            return newCopy(newResolveStatus, newIgnoresFallbacks, origin());
        else
            return this;
    }

    @Override
    public AbstractConfigObject withFallback(ConfigMergeable mergeable) {
        return (AbstractConfigObject) super.withFallback(mergeable);
    }

    static ConfigOrigin mergeOrigins(
            Collection<? extends AbstractConfigValue> stack) {
        if (stack.isEmpty())
            throw new ConfigException.BugOrBroken(
                    "can't merge origins on empty list");
        List<ConfigOrigin> origins = new ArrayList<ConfigOrigin>();
        ConfigOrigin firstOrigin = null;
        int numMerged = 0;
        for (AbstractConfigValue v : stack) {
            if (firstOrigin == null)
                firstOrigin = v.origin();

            if (v instanceof AbstractConfigObject
                    && ((AbstractConfigObject) v).resolveStatus() == ResolveStatus.RESOLVED
                    && ((ConfigObject) v).isEmpty()) {
                // don't include empty files or the .empty()
                // config in the description, since they are
                // likely to be "implementation details"
            } else {
                origins.add(v.origin());
                numMerged += 1;
            }
        }

        if (numMerged == 0) {
            // the configs were all empty, so just use the first one
            origins.add(firstOrigin);
        }

        return SimpleConfigOrigin.mergeOrigins(origins);
    }

    static ConfigOrigin mergeOrigins(AbstractConfigObject... stack) {
        return mergeOrigins(Arrays.asList(stack));
    }

    private AbstractConfigObject modify(Modifier modifier,
            ResolveStatus newResolveStatus) {
        Map<String, AbstractConfigValue> changes = null;
        for (String k : keySet()) {
            AbstractConfigValue v = peek(k);
            // "modified" may be null, which means remove the child;
            // to do that we put null in the "changes" map.
            AbstractConfigValue modified = modifier.modifyChild(v);
            if (modified != v) {
                if (changes == null)
                    changes = new HashMap<String, AbstractConfigValue>();
                changes.put(k, modified);
            }
        }
        if (changes == null) {
            return newCopy(newResolveStatus, ignoresFallbacks(), origin());
        } else {
            Map<String, AbstractConfigValue> modified = new HashMap<String, AbstractConfigValue>();
            for (String k : keySet()) {
                if (changes.containsKey(k)) {
                    AbstractConfigValue newValue = changes.get(k);
                    if (newValue != null) {
                        modified.put(k, newValue);
                    } else {
                        // remove this child; don't put it in the new map.
                    }
                } else {
                    modified.put(k, peek(k));
                }
            }
            return new SimpleConfigObject(origin(), modified, newResolveStatus,
                    ignoresFallbacks());
        }
    }

    @Override
    AbstractConfigObject resolveSubstitutions(final SubstitutionResolver resolver,
            final int depth,
            final ConfigResolveOptions options) {
        if (resolveStatus() == ResolveStatus.RESOLVED)
            return this;

        return modify(new Modifier() {

            @Override
            public AbstractConfigValue modifyChild(AbstractConfigValue v) {
                return resolver.resolve(v, depth, options);
            }

        }, ResolveStatus.RESOLVED);
    }

    @Override
    AbstractConfigObject relativized(final Path prefix) {
        return modify(new Modifier() {

            @Override
            public AbstractConfigValue modifyChild(AbstractConfigValue v) {
                return v.relativized(prefix);
            }

        }, resolveStatus());
    }

    @Override
    public AbstractConfigValue get(Object key) {
        if (key instanceof String)
            return peek((String) key);
        else
            return null;
    }

    @Override
    protected void render(StringBuilder sb, int indent, boolean formatted) {
        if (isEmpty()) {
            sb.append("{}");
        } else {
            sb.append("{");
            if (formatted)
                sb.append('\n');
            for (String k : keySet()) {
                AbstractConfigValue v = peek(k);
                if (formatted) {
                    indent(sb, indent + 1);
                    sb.append("# ");
                    sb.append(v.origin().description());
                    sb.append("\n");
                    for (String comment : v.origin().comments()) {
                        indent(sb, indent + 1);
                        sb.append("# ");
                        sb.append(comment);
                        sb.append("\n");
                    }
                    indent(sb, indent + 1);
                }
                v.render(sb, indent + 1, k, formatted);
                sb.append(",");
                if (formatted)
                    sb.append('\n');
            }
            // chop comma or newline
            sb.setLength(sb.length() - 1);
            if (formatted) {
                sb.setLength(sb.length() - 1); // also chop comma
                sb.append("\n"); // put a newline back
                indent(sb, indent);
            }
            sb.append("}");
        }
    }

    private static boolean mapEquals(Map<String, ConfigValue> a,
            Map<String, ConfigValue> b) {
        Set<String> aKeys = a.keySet();
        Set<String> bKeys = b.keySet();

        if (!aKeys.equals(bKeys))
            return false;

        for (String key : aKeys) {
            if (!a.get(key).equals(b.get(key)))
                return false;
        }
        return true;
    }

    private static int mapHash(Map<String, ConfigValue> m) {
        // the keys have to be sorted, otherwise we could be equal
        // to another map but have a different hashcode.
        List<String> keys = new ArrayList<String>();
        keys.addAll(m.keySet());
        Collections.sort(keys);

        int valuesHash = 0;
        for (String k : keys) {
            valuesHash += m.get(k).hashCode();
        }
        return 41 * (41 + keys.hashCode()) + valuesHash;
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof ConfigObject;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality.
        // neither are other "extras" like ignoresFallbacks or resolve status.
        if (other instanceof ConfigObject) {
            // optimization to avoid unwrapped() for two ConfigObject,
            // which is what AbstractConfigValue does.
            return canEqual(other) && mapEquals(this, ((ConfigObject) other));
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality
        // neither are other "extras" like ignoresFallbacks or resolve status.
        return mapHash(this);
    }

    private static UnsupportedOperationException weAreImmutable(String method) {
        return new UnsupportedOperationException(
                "ConfigObject is immutable, you can't call Map.'" + method
                        + "'");
    }

    @Override
    public void clear() {
        throw weAreImmutable("clear");
    }

    @Override
    public ConfigValue put(String arg0, ConfigValue arg1) {
        throw weAreImmutable("put");
    }

    @Override
    public void putAll(Map<? extends String, ? extends ConfigValue> arg0) {
        throw weAreImmutable("putAll");
    }

    @Override
    public ConfigValue remove(Object arg0) {
        throw weAreImmutable("remove");
    }
}
