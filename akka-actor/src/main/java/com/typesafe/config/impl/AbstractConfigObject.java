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
    final private SimpleConfig config;

    protected AbstractConfigObject(ConfigOrigin origin) {
        super(origin);
        this.config = new SimpleConfig(this);
    }

    @Override
    public SimpleConfig toConfig() {
        return config;
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
    protected ConfigValue peekPath(Path path, SubstitutionResolver resolver,
            int depth, ConfigResolveOptions options) {
        return peekPath(this, path, resolver, depth, options);
    }

    private static ConfigValue peekPath(AbstractConfigObject self, Path path,
            SubstitutionResolver resolver, int depth,
            ConfigResolveOptions options) {
        String key = path.first();
        Path next = path.remainder();

        if (next == null) {
            ConfigValue v = self.peek(key, resolver, depth, options);
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

    protected abstract AbstractConfigObject newCopy(ResolveStatus status);

    @Override
    public AbstractConfigObject withFallbacks(ConfigMergeable... others) {
        return (AbstractConfigObject) super.withFallbacks(others);
    }

    @Override
    public AbstractConfigObject withFallback(ConfigMergeable mergeable) {
        ConfigValue other = mergeable.toValue();

        if (other instanceof Unmergeable) {
            List<AbstractConfigValue> stack = new ArrayList<AbstractConfigValue>();
            stack.add(this);
            stack.addAll(((Unmergeable) other).unmergedValues());
            return new ConfigDelayedMergeObject(mergeOrigins(stack), stack);
        } else if (other instanceof AbstractConfigObject) {
            AbstractConfigObject fallback = (AbstractConfigObject) other;
            if (fallback.isEmpty()) {
                return this; // nothing to do
            } else {
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
                    if (kept.resolveStatus() == ResolveStatus.UNRESOLVED)
                        allResolved = false;
                }
                return new SimpleConfigObject(mergeOrigins(this, fallback),
                        merged, ResolveStatus.fromBoolean(allResolved));
            }
        } else {
            // falling back to a non-object has no effect, we just override
            // primitive values.
            return this;
        }
    }

    static ConfigOrigin mergeOrigins(
            Collection<? extends AbstractConfigValue> stack) {
        if (stack.isEmpty())
            throw new ConfigException.BugOrBroken(
                    "can't merge origins on empty list");
        final String prefix = "merge of ";
        StringBuilder sb = new StringBuilder();
        ConfigOrigin firstOrigin = null;
        int numMerged = 0;
        for (AbstractConfigValue v : stack) {
            if (firstOrigin == null)
                firstOrigin = v.origin();

            String desc = v.origin().description();
            if (desc.startsWith(prefix))
                desc = desc.substring(prefix.length());

            if (v instanceof ConfigObject && ((ConfigObject) v).isEmpty()) {
                // don't include empty files or the .empty()
                // config in the description, since they are
                // likely to be "implementation details"
            } else {
                sb.append(desc);
                sb.append(",");
                numMerged += 1;
            }
        }
        if (numMerged > 0) {
            sb.setLength(sb.length() - 1); // chop comma
            if (numMerged > 1) {
                return new SimpleConfigOrigin(prefix + sb.toString());
            } else {
                return new SimpleConfigOrigin(sb.toString());
            }
        } else {
            // the configs were all empty.
            return firstOrigin;
        }
    }

    static ConfigOrigin mergeOrigins(AbstractConfigObject... stack) {
        return mergeOrigins(Arrays.asList(stack));
    }

    private AbstractConfigObject modify(Modifier modifier,
            ResolveStatus newResolveStatus) {
        Map<String, AbstractConfigValue> changes = null;
        for (String k : keySet()) {
            AbstractConfigValue v = peek(k);
            AbstractConfigValue modified = modifier.modifyChild(v);
            if (modified != v) {
                if (changes == null)
                    changes = new HashMap<String, AbstractConfigValue>();
                changes.put(k, modified);
            }
        }
        if (changes == null) {
            return newCopy(newResolveStatus);
        } else {
            Map<String, AbstractConfigValue> modified = new HashMap<String, AbstractConfigValue>();
            for (String k : keySet()) {
                if (changes.containsKey(k)) {
                    modified.put(k, changes.get(k));
                } else {
                    modified.put(k, peek(k));
                }
            }
            return new SimpleConfigObject(origin(), modified,
                    newResolveStatus);
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
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(valueType().name());
        sb.append("(");
        for (String k : keySet()) {
            sb.append(k);
            sb.append("->");
            sb.append(peek(k).toString());
            sb.append(",");
        }
        if (!keySet().isEmpty())
            sb.setLength(sb.length() - 1); // chop comma
        sb.append(")");
        return sb.toString();
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
        // note that "origin" is deliberately NOT part of equality
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
