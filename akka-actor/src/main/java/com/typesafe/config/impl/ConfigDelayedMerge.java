/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValueType;

/**
 * The issue here is that we want to first merge our stack of config files, and
 * then we want to evaluate substitutions. But if two substitutions both expand
 * to an object, we might need to merge those two objects. Thus, we can't ever
 * "override" a substitution when we do a merge; instead we have to save the
 * stack of values that should be merged, and resolve the merge when we evaluate
 * substitutions.
 */
final class ConfigDelayedMerge extends AbstractConfigValue implements
        Unmergeable {

    // earlier items in the stack win
    final private List<AbstractConfigValue> stack;
    final private boolean ignoresFallbacks;

    ConfigDelayedMerge(ConfigOrigin origin, List<AbstractConfigValue> stack,
            boolean ignoresFallbacks) {
        super(origin);
        this.stack = stack;
        this.ignoresFallbacks = ignoresFallbacks;
        if (stack.isEmpty())
            throw new ConfigException.BugOrBroken(
                    "creating empty delayed merge value");

        for (AbstractConfigValue v : stack) {
            if (v instanceof ConfigDelayedMerge || v instanceof ConfigDelayedMergeObject)
                throw new ConfigException.BugOrBroken(
                        "placed nested DelayedMerge in a ConfigDelayedMerge, should have consolidated stack");
        }
    }

    ConfigDelayedMerge(ConfigOrigin origin, List<AbstractConfigValue> stack) {
        this(origin, stack, false /* ignoresFallbacks */);
    }

    @Override
    public ConfigValueType valueType() {
        throw new ConfigException.NotResolved(
                "called valueType() on value with unresolved substitutions, need to resolve first");
    }

    @Override
    public Object unwrapped() {
        throw new ConfigException.NotResolved(
                "called unwrapped() on value with unresolved substitutions, need to resolve first");
    }

    @Override
    AbstractConfigValue resolveSubstitutions(SubstitutionResolver resolver,
            int depth, ConfigResolveOptions options) {
        return resolveSubstitutions(stack, resolver, depth, options);
    }

    // static method also used by ConfigDelayedMergeObject
    static AbstractConfigValue resolveSubstitutions(
            List<AbstractConfigValue> stack, SubstitutionResolver resolver,
            int depth, ConfigResolveOptions options) {
        // to resolve substitutions, we need to recursively resolve
        // the stack of stuff to merge, and merge the stack so
        // we won't be a delayed merge anymore.

        AbstractConfigValue merged = null;
        for (AbstractConfigValue v : stack) {
            AbstractConfigValue resolved = resolver.resolve(v, depth, options);
            if (merged == null)
                merged = resolved;
            else
                merged = merged.withFallback(resolved);
        }

        return merged;
    }

    @Override
    ResolveStatus resolveStatus() {
        return ResolveStatus.UNRESOLVED;
    }

    @Override
    ConfigDelayedMerge relativized(Path prefix) {
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        for (AbstractConfigValue o : stack) {
            newStack.add(o.relativized(prefix));
        }
        return new ConfigDelayedMerge(origin(), newStack, ignoresFallbacks);
    }

    @Override
    protected boolean ignoresFallbacks() {
        return ignoresFallbacks;
    }

    @Override
    protected final ConfigDelayedMerge mergedWithTheUnmergeable(Unmergeable fallback) {
        if (ignoresFallbacks)
            throw new ConfigException.BugOrBroken("should not be reached");

        // if we turn out to be an object, and the fallback also does,
        // then a merge may be required; delay until we resolve.
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        newStack.addAll(stack);
        newStack.addAll(fallback.unmergedValues());
        return new ConfigDelayedMerge(AbstractConfigObject.mergeOrigins(newStack), newStack,
                ((AbstractConfigValue) fallback).ignoresFallbacks());
    }

    @Override
    protected final ConfigDelayedMerge mergedWithObject(AbstractConfigObject fallback) {
        if (ignoresFallbacks)
            throw new ConfigException.BugOrBroken("should not be reached");

        // if we turn out to be an object, and the fallback also does,
        // then a merge may be required; delay until we resolve.
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        newStack.addAll(stack);
        newStack.add(fallback);
        return new ConfigDelayedMerge(AbstractConfigObject.mergeOrigins(newStack), newStack,
                fallback.ignoresFallbacks());
    }

    @Override
    public Collection<AbstractConfigValue> unmergedValues() {
        return stack;
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof ConfigDelayedMerge;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (other instanceof ConfigDelayedMerge) {
            return canEqual(other)
                    && this.stack.equals(((ConfigDelayedMerge) other).stack);
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
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DELAYED_MERGE");
        sb.append("(");
        for (Object s : stack) {
            sb.append(s.toString());
            sb.append(",");
        }
        sb.setLength(sb.length() - 1); // chop comma
        sb.append(")");
        return sb.toString();
    }
}
