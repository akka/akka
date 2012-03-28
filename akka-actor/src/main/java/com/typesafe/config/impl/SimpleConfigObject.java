/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;

final class SimpleConfigObject extends AbstractConfigObject {

    private static final long serialVersionUID = 1L;

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

        // Kind of an expensive debug check. Comment out?
        if (status != ResolveStatus.fromValues(value.values()))
            throw new ConfigException.BugOrBroken("Wrong resolved status on " + this);
    }

    SimpleConfigObject(ConfigOrigin origin,
            Map<String, AbstractConfigValue> value) {
        this(origin, value, ResolveStatus.fromValues(value.values()), false /* ignoresFallbacks */);
    }

    @Override
    public SimpleConfigObject withOnlyKey(String key) {
        return withOnlyPath(Path.newKey(key));
    }

    @Override
    public SimpleConfigObject withoutKey(String key) {
        return withoutPath(Path.newKey(key));
    }

    // gets the object with only the path if the path
    // exists, otherwise null if it doesn't. this ensures
    // that if we have { a : { b : 42 } } and do
    // withOnlyPath("a.b.c") that we don't keep an empty
    // "a" object.
    @Override
    protected SimpleConfigObject withOnlyPathOrNull(Path path) {
        String key = path.first();
        Path next = path.remainder();
        AbstractConfigValue v = value.get(key);

        if (next != null) {
            if (v != null && (v instanceof AbstractConfigObject)) {
                v = ((AbstractConfigObject) v).withOnlyPathOrNull(next);
            } else {
                // if the path has more elements but we don't have an object,
                // then the rest of the path does not exist.
                v = null;
            }
        }

        if (v == null) {
            return null;
        } else {
            return new SimpleConfigObject(origin(), Collections.singletonMap(key, v),
                    v.resolveStatus(), ignoresFallbacks);
        }
    }

    @Override
    SimpleConfigObject withOnlyPath(Path path) {
        SimpleConfigObject o = withOnlyPathOrNull(path);
        if (o == null) {
            return new SimpleConfigObject(origin(),
                    Collections.<String, AbstractConfigValue> emptyMap(), ResolveStatus.RESOLVED,
                    ignoresFallbacks);
        } else {
            return o;
        }
    }

    @Override
    SimpleConfigObject withoutPath(Path path) {
        String key = path.first();
        Path next = path.remainder();
        AbstractConfigValue v = value.get(key);

        if (v != null && next != null && v instanceof AbstractConfigObject) {
            v = ((AbstractConfigObject) v).withoutPath(next);
            Map<String, AbstractConfigValue> updated = new HashMap<String, AbstractConfigValue>(
                    value);
            updated.put(key, v);
            return new SimpleConfigObject(origin(), updated, ResolveStatus.fromValues(updated
                    .values()), ignoresFallbacks);
        } else if (next != null || v == null) {
            // can't descend, nothing to remove
            return this;
        } else {
            Map<String, AbstractConfigValue> smaller = new HashMap<String, AbstractConfigValue>(
                    value.size() - 1);
            for (Map.Entry<String, AbstractConfigValue> old : value.entrySet()) {
                if (!old.getKey().equals(key))
                    smaller.put(old.getKey(), old.getValue());
            }
            return new SimpleConfigObject(origin(), smaller, ResolveStatus.fromValues(smaller
                    .values()), ignoresFallbacks);
        }
    }

    @Override
    protected AbstractConfigValue attemptPeekWithPartialResolve(String key) {
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
    protected SimpleConfigObject mergedWithObject(AbstractConfigObject abstractFallback) {
        if (ignoresFallbacks())
            throw new ConfigException.BugOrBroken("should not be reached");

        if (!(abstractFallback instanceof SimpleConfigObject)) {
            throw new ConfigException.BugOrBroken(
                    "should not be reached (merging non-SimpleConfigObject)");
        }

        SimpleConfigObject fallback = (SimpleConfigObject) abstractFallback;

        boolean changed = false;
        boolean allResolved = true;
        Map<String, AbstractConfigValue> merged = new HashMap<String, AbstractConfigValue>();
        Set<String> allKeys = new HashSet<String>();
        allKeys.addAll(this.keySet());
        allKeys.addAll(fallback.keySet());
        for (String key : allKeys) {
            AbstractConfigValue first = this.value.get(key);
            AbstractConfigValue second = fallback.value.get(key);
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

    private SimpleConfigObject modify(NoExceptionsModifier modifier) {
        try {
            return modifyMayThrow(modifier);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException.BugOrBroken("unexpected checked exception", e);
        }
    }

    private SimpleConfigObject modifyMayThrow(Modifier modifier) throws Exception {
        Map<String, AbstractConfigValue> changes = null;
        for (String k : keySet()) {
            AbstractConfigValue v = value.get(k);
            // "modified" may be null, which means remove the child;
            // to do that we put null in the "changes" map.
            AbstractConfigValue modified = modifier.modifyChildMayThrow(k, v);
            if (modified != v) {
                if (changes == null)
                    changes = new HashMap<String, AbstractConfigValue>();
                changes.put(k, modified);
            }
        }
        if (changes == null) {
            return newCopy(resolveStatus(), ignoresFallbacks(), origin());
        } else {
            Map<String, AbstractConfigValue> modified = new HashMap<String, AbstractConfigValue>();
            boolean sawUnresolved = false;
            for (String k : keySet()) {
                if (changes.containsKey(k)) {
                    AbstractConfigValue newValue = changes.get(k);
                    if (newValue != null) {
                        modified.put(k, newValue);
                        if (newValue.resolveStatus() == ResolveStatus.UNRESOLVED)
                            sawUnresolved = true;
                    } else {
                        // remove this child; don't put it in the new map.
                    }
                } else {
                    AbstractConfigValue newValue = value.get(k);
                    modified.put(k, newValue);
                    if (newValue.resolveStatus() == ResolveStatus.UNRESOLVED)
                        sawUnresolved = true;
                }
            }
            return new SimpleConfigObject(origin(), modified,
                    sawUnresolved ? ResolveStatus.UNRESOLVED : ResolveStatus.RESOLVED,
                    ignoresFallbacks());
        }
    }

    @Override
    AbstractConfigObject resolveSubstitutions(final SubstitutionResolver resolver, final int depth,
            final ConfigResolveOptions options, final Path restrictToChildOrNull)
            throws NotPossibleToResolve, NeedsFullResolve {
        if (resolveStatus() == ResolveStatus.RESOLVED)
            return this;

        try {
            return modifyMayThrow(new Modifier() {

                @Override
                public AbstractConfigValue modifyChildMayThrow(String key, AbstractConfigValue v)
                        throws NotPossibleToResolve, NeedsFullResolve {
                    if (restrictToChildOrNull != null) {
                        if (key.equals(restrictToChildOrNull.first())) {
                            Path remainder = restrictToChildOrNull.remainder();
                            if (remainder != null) {
                                return resolver.resolve(v, depth, options, remainder);
                            } else {
                                // we don't want to resolve the leaf child.
                                return v;
                            }
                        } else {
                            // not in the restrictToChild path
                            return v;
                        }
                    } else {
                        // no restrictToChild, resolve everything
                        return resolver.resolve(v, depth, options, null);
                    }
                }

            });
        } catch (NotPossibleToResolve e) {
            throw e;
        } catch (NeedsFullResolve e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException.BugOrBroken("unexpected checked exception", e);
        }
    }

    @Override
    SimpleConfigObject relativized(final Path prefix) {
        return modify(new NoExceptionsModifier() {

            @Override
            public AbstractConfigValue modifyChild(String key, AbstractConfigValue v) {
                return v.relativized(prefix);
            }

        });
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
                AbstractConfigValue v;
                v = value.get(k);

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

    @Override
    public AbstractConfigValue get(Object key) {
        return value.get(key);
    }

    private static boolean mapEquals(Map<String, ConfigValue> a, Map<String, ConfigValue> b) {
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
