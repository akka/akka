/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigMergeable;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;

// This is just like ConfigDelayedMerge except we know statically
// that it will turn out to be an object.
final class ConfigDelayedMergeObject extends AbstractConfigObject implements
        Unmergeable {

    private static final long serialVersionUID = 1L;

    final private List<AbstractConfigValue> stack;
    final private boolean ignoresFallbacks;

    ConfigDelayedMergeObject(ConfigOrigin origin,
            List<AbstractConfigValue> stack) {
        this(origin, stack, false /* ignoresFallbacks */);
    }

    ConfigDelayedMergeObject(ConfigOrigin origin, List<AbstractConfigValue> stack,
            boolean ignoresFallbacks) {
        super(origin);
        this.stack = stack;
        this.ignoresFallbacks = ignoresFallbacks;

        if (stack.isEmpty())
            throw new ConfigException.BugOrBroken(
                    "creating empty delayed merge object");
        if (!(stack.get(0) instanceof AbstractConfigObject))
            throw new ConfigException.BugOrBroken(
                    "created a delayed merge object not guaranteed to be an object");

        for (AbstractConfigValue v : stack) {
            if (v instanceof ConfigDelayedMerge || v instanceof ConfigDelayedMergeObject)
                throw new ConfigException.BugOrBroken(
                        "placed nested DelayedMerge in a ConfigDelayedMergeObject, should have consolidated stack");
        }
    }

    @Override
    protected ConfigDelayedMergeObject newCopy(ResolveStatus status, boolean ignoresFallbacks,
            ConfigOrigin origin) {
        if (status != resolveStatus())
            throw new ConfigException.BugOrBroken(
                    "attempt to create resolved ConfigDelayedMergeObject");
        return new ConfigDelayedMergeObject(origin, stack, ignoresFallbacks);
    }

    @Override
    AbstractConfigObject resolveSubstitutions(SubstitutionResolver resolver, int depth,
            ConfigResolveOptions options, Path restrictToChildOrNull) throws NotPossibleToResolve,
            NeedsFullResolve {
        AbstractConfigValue merged = ConfigDelayedMerge.resolveSubstitutions(stack, resolver,
                depth, options, restrictToChildOrNull);
        if (merged instanceof AbstractConfigObject) {
            return (AbstractConfigObject) merged;
        } else {
            throw new ConfigException.BugOrBroken(
                    "somehow brokenly merged an object and didn't get an object");
        }
    }

    @Override
    ResolveStatus resolveStatus() {
        return ResolveStatus.UNRESOLVED;
    }

    @Override
    ConfigDelayedMergeObject relativized(Path prefix) {
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        for (AbstractConfigValue o : stack) {
            newStack.add(o.relativized(prefix));
        }
        return new ConfigDelayedMergeObject(origin(), newStack,
                ignoresFallbacks);
    }

    @Override
    protected boolean ignoresFallbacks() {
        return ignoresFallbacks;
    }

    @Override
    protected ConfigDelayedMergeObject mergedWithObject(AbstractConfigObject fallback) {
        if (ignoresFallbacks)
            throw new ConfigException.BugOrBroken("should not be reached");

        // since we are an object, and the fallback is, we'll need to
        // merge the fallback once we resolve.
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        newStack.addAll(stack);
        newStack.add(fallback);
        return new ConfigDelayedMergeObject(AbstractConfigObject.mergeOrigins(newStack), newStack,
                fallback.ignoresFallbacks());
    }

    @Override
    public ConfigDelayedMergeObject withFallback(ConfigMergeable mergeable) {
        return (ConfigDelayedMergeObject) super.withFallback(mergeable);
    }

    @Override
    public ConfigDelayedMergeObject withOnlyKey(String key) {
        throw notResolved();
    }

    @Override
    public ConfigDelayedMergeObject withoutKey(String key) {
        throw notResolved();
    }

    @Override
    protected AbstractConfigObject withOnlyPathOrNull(Path path) {
        throw notResolved();
    }

    @Override
    AbstractConfigObject withOnlyPath(Path path) {
        throw notResolved();
    }

    @Override
    AbstractConfigObject withoutPath(Path path) {
        throw notResolved();
    }

    @Override
    public Collection<AbstractConfigValue> unmergedValues() {
        return stack;
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof ConfigDelayedMergeObject;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (other instanceof ConfigDelayedMergeObject) {
            return canEqual(other)
                    && this.stack
                            .equals(((ConfigDelayedMergeObject) other).stack);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality
        return stack.hashCode();
    }

    @Override
    protected void render(StringBuilder sb, int indent, String atKey, boolean formatted) {
        ConfigDelayedMerge.render(stack, sb, indent, atKey, formatted);
    }

    @Override
    protected void render(StringBuilder sb, int indent, boolean formatted) {
        render(sb, indent, null, formatted);
    }

    private static ConfigException notResolved() {
        return new ConfigException.NotResolved(
                "bug: this object has not had substitutions resolved, so can't be used");
    }

    @Override
    public Map<String, Object> unwrapped() {
        throw notResolved();
    }

    @Override
    public AbstractConfigValue get(Object key) {
        throw notResolved();
    }

    @Override
    public boolean containsKey(Object key) {
        throw notResolved();
    }

    @Override
    public boolean containsValue(Object value) {
        throw notResolved();
    }

    @Override
    public Set<java.util.Map.Entry<String, ConfigValue>> entrySet() {
        throw notResolved();
    }

    @Override
    public boolean isEmpty() {
        throw notResolved();
    }

    @Override
    public Set<String> keySet() {
        throw notResolved();
    }

    @Override
    public int size() {
        throw notResolved();
    }

    @Override
    public Collection<ConfigValue> values() {
        throw notResolved();
    }

    @Override
    protected AbstractConfigValue attemptPeekWithPartialResolve(String key) throws NeedsFullResolve {
        // a partial resolve of a ConfigDelayedMergeObject always results in a
        // SimpleConfigObject because all the substitutions in the stack get
        // resolved in order to look up the partial.
        // So we know here that we have not been resolved at all even
        // partially.
        // Given that, all this code is probably gratuitous, since the app code
        // is likely broken. But in general we only throw NotResolved if you try
        // to touch the exact key that isn't resolved, so this is in that
        // spirit.

        // this function should never return null; if we know a value doesn't
        // exist, then there would be no reason for the merge to be delayed
        // (i.e. as long as some stuff is unmerged, the value may be non-null).

        // we'll be able to return a key if we have a value that ignores
        // fallbacks, prior to any unmergeable values.
        for (AbstractConfigValue layer : stack) {
            if (layer instanceof AbstractConfigObject) {
                AbstractConfigObject objectLayer = (AbstractConfigObject) layer;
                AbstractConfigValue v = objectLayer.attemptPeekWithPartialResolve(key);
                if (v != null) {
                    if (v.ignoresFallbacks()) {
                        // we know we won't need to merge anything in to this
                        // value
                        return v;
                    } else {
                        // we can't return this value because we know there are
                        // unmergeable values later in the stack that may
                        // contain values that need to be merged with this
                        // value. we'll throw the exception when we get to those
                        // unmergeable values, so continue here.
                        continue;
                    }
                } else if (layer instanceof Unmergeable) {
                    // an unmergeable object (which would be another
                    // ConfigDelayedMergeObject) can't know that a key is
                    // missing, so it can't return null; it can only return a
                    // value or throw NotPossibleToResolve
                    throw new ConfigException.BugOrBroken(
                            "should not be reached: unmergeable object returned null value");
                } else {
                    // a non-unmergeable AbstractConfigObject that returned null
                    // for the key in question is not relevant, we can keep
                    // looking for a value.
                    continue;
                }
            } else if (layer instanceof Unmergeable) {
                throw new NeedsFullResolve("Key '" + key + "' is not available at '"
                        + origin().description() + "' because value at '"
                        + layer.origin().description()
                        + "' has not been resolved and may turn out to contain '" + key + "'."
                        + " Be sure to Config.resolve() before using a config object.");
            } else {
                // non-object, but not unresolved, like an integer or something.
                // has no children so the one we're after won't be in it.
                // this should always be overridden by an object though so
                // ideally we never build a stack that would have this in it.
                continue;
            }
        }
        // If we get here, then we never found an unmergeable which means
        // the ConfigDelayedMergeObject should not have existed. some
        // invariant was violated.
        throw new ConfigException.BugOrBroken(
                "Delayed merge stack does not contain any unmergeable values");

    }

    // This ridiculous hack is because some JDK versions apparently can't
    // serialize an array, which is used to implement ArrayList and EmptyList.
    // maybe
    // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6446627
    private Object writeReplace() throws ObjectStreamException {
        // switch to LinkedList
        return new ConfigDelayedMergeObject(origin(),
                new java.util.LinkedList<AbstractConfigValue>(stack), ignoresFallbacks);
    }
}
