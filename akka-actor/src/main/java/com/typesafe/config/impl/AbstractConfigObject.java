/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    @Override
    abstract public AbstractConfigObject withOnlyKey(String key);

    @Override
    abstract public AbstractConfigObject withoutKey(String key);

    abstract protected AbstractConfigObject withOnlyPathOrNull(Path path);

    abstract AbstractConfigObject withOnlyPath(Path path);

    abstract AbstractConfigObject withoutPath(Path path);

    /**
     * This looks up the key with no transformation or type conversion of any
     * kind, and returns null if the key is not present. The object must be
     * resolved; use attemptPeekWithPartialResolve() if it is not.
     *
     * @param key
     * @return the unmodified raw value or null
     */
    protected final AbstractConfigValue peekAssumingResolved(String key, String originalPath) {
        try {
            return attemptPeekWithPartialResolve(key);
        } catch (NeedsFullResolve e) {
            throw new ConfigException.NotResolved(originalPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Look up the key on an only-partially-resolved object, with no
     * transformation or type conversion of any kind; if 'this' is not resolved
     * then try to look up the key anyway if possible.
     *
     * @param key
     *            key to look up
     * @return the value of the key, or null if known not to exist
     * @throws NeedsFullResolve
     *             if can't figure out key's value or can't know whether it
     *             exists
     */
    protected abstract AbstractConfigValue attemptPeekWithPartialResolve(String key)
            throws NeedsFullResolve;

    /**
     * Looks up the path with no transformation, type conversion, or exceptions
     * (just returns null if path not found). Does however resolve the path, if
     * resolver != null.
     *
     * @throws NotPossibleToResolve
     */
    protected AbstractConfigValue peekPath(Path path, SubstitutionResolver resolver, int depth,
            ConfigResolveOptions options) throws NotPossibleToResolve, NeedsFullResolve {
        return peekPath(this, path, resolver, depth, options);
    }

    /**
     * Looks up the path and throws public API exceptions (ConfigException).
     * Doesn't do any resolution, will throw if any is needed.
     */
    AbstractConfigValue peekPathWithExternalExceptions(Path path) {
        try {
            return peekPath(this, path, null, 0, null);
        } catch (NotPossibleToResolve e) {
            throw e.exportException(origin(), path.render());
        } catch (NeedsFullResolve e) {
            throw new ConfigException.NotResolved(
                    "need to resolve() this Config before looking up value at " + path.render(), e);
        }
    }

    // as a side effect, peekPath() will have to resolve all parents of the
    // child being peeked, but NOT the child itself. Caller has to resolve
    // the child itself if needed.
    private static AbstractConfigValue peekPath(AbstractConfigObject self, Path path,
            SubstitutionResolver resolver, int depth, ConfigResolveOptions options)
            throws NotPossibleToResolve, NeedsFullResolve {
        if (resolver != null) {
            // walk down through the path resolving only things along that path,
            // and then recursively call ourselves with no resolver.
            AbstractConfigValue partiallyResolved = resolver.resolve(self, depth, options, path);
            if (partiallyResolved instanceof AbstractConfigObject) {
                return peekPath((AbstractConfigObject) partiallyResolved, path, null, 0, null);
            } else {
                throw new ConfigException.BugOrBroken("resolved object to non-object " + self
                        + " to " + partiallyResolved);
            }
        } else {
            // with no resolver, we'll fail if anything along the path can't be
            // looked at without resolving.
            Path next = path.remainder();
            AbstractConfigValue v = self.attemptPeekWithPartialResolve(path.first());

            if (next == null) {
                return v;
            } else {
                if (v instanceof AbstractConfigObject) {
                    return peekPath((AbstractConfigObject) v, next, null, 0, null);
                } else {
                    return null;
                }
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
    protected abstract AbstractConfigObject mergedWithObject(AbstractConfigObject fallback);

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

    @Override
    abstract AbstractConfigObject resolveSubstitutions(final SubstitutionResolver resolver,
            int depth, ConfigResolveOptions options, Path restrictToChildOrNull)
            throws NotPossibleToResolve, NeedsFullResolve;

    @Override
    abstract AbstractConfigObject relativized(final Path prefix);

    @Override
    public abstract AbstractConfigValue get(Object key);

    @Override
    protected abstract void render(StringBuilder sb, int indent, boolean formatted);

    private static UnsupportedOperationException weAreImmutable(String method) {
        return new UnsupportedOperationException("ConfigObject is immutable, you can't call Map."
                + method);
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
