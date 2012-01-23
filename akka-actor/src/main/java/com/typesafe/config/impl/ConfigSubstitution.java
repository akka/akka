/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * A ConfigSubstitution represents a value with one or more substitutions in it;
 * it can resolve to a value of any type, though if the substitution has more
 * than one piece it always resolves to a string via value concatenation.
 */
final class ConfigSubstitution extends AbstractConfigValue implements
        Unmergeable {

    // this is a list of String and SubstitutionExpression where the
    // SubstitutionExpression has to be resolved to values, then if there's more
    // than one piece everything is stringified and concatenated
    final private List<Object> pieces;
    // the length of any prefixes added with relativized()
    final private int prefixLength;
    final private boolean ignoresFallbacks;

    ConfigSubstitution(ConfigOrigin origin, List<Object> pieces) {
        this(origin, pieces, 0, false);
    }

    private ConfigSubstitution(ConfigOrigin origin, List<Object> pieces,
            int prefixLength, boolean ignoresFallbacks) {
        super(origin);
        this.pieces = pieces;
        this.prefixLength = prefixLength;
        this.ignoresFallbacks = ignoresFallbacks;
        for (Object p : pieces) {
            if (p instanceof Path)
                throw new RuntimeException("broken here");
        }
    }

    @Override
    public ConfigValueType valueType() {
        throw new ConfigException.NotResolved(
                "need to call resolve() on root config; tried to get value type on an unresolved substitution: "
                        + this);
    }

    @Override
    public Object unwrapped() {
        throw new ConfigException.NotResolved(
                "need to call resolve() on root config; tried to unwrap an unresolved substitution: "
                        + this);
    }

    @Override
    protected ConfigSubstitution newCopy(boolean ignoresFallbacks, ConfigOrigin newOrigin) {
        return new ConfigSubstitution(newOrigin, pieces, prefixLength, ignoresFallbacks);
    }

    @Override
    protected boolean ignoresFallbacks() {
        return ignoresFallbacks;
    }

    @Override
    protected AbstractConfigValue mergedWithTheUnmergeable(Unmergeable fallback) {
        if (ignoresFallbacks)
            throw new ConfigException.BugOrBroken("should not be reached");

        // if we turn out to be an object, and the fallback also does,
        // then a merge may be required; delay until we resolve.
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        newStack.add(this);
        newStack.addAll(fallback.unmergedValues());
        return new ConfigDelayedMerge(AbstractConfigObject.mergeOrigins(newStack), newStack,
                ((AbstractConfigValue) fallback).ignoresFallbacks());
    }

    @Override
    protected AbstractConfigValue mergedWithObject(AbstractConfigObject fallback) {
        if (ignoresFallbacks)
            throw new ConfigException.BugOrBroken("should not be reached");

        // if we turn out to be an object, and the fallback also does,
        // then a merge may be required; delay until we resolve.
        List<AbstractConfigValue> newStack = new ArrayList<AbstractConfigValue>();
        newStack.add(this);
        newStack.add(fallback);
        return new ConfigDelayedMerge(AbstractConfigObject.mergeOrigins(newStack), newStack,
                fallback.ignoresFallbacks());
    }

    @Override
    public Collection<ConfigSubstitution> unmergedValues() {
        return Collections.singleton(this);
    }

    List<Object> pieces() {
        return pieces;
    }

    // larger than anyone would ever want
    private static final int MAX_DEPTH = 100;

    private ConfigValue findInObject(AbstractConfigObject root,
            SubstitutionResolver resolver, /* null if we should not have refs */
            Path subst, int depth, ConfigResolveOptions options) {
        if (depth > MAX_DEPTH) {
            throw new ConfigException.BadValue(origin(), subst.render(),
                    "Substitution ${" + subst.render()
                            + "} is part of a cycle of substitutions");
        }

        ConfigValue result = root.peekPath(subst, resolver, depth, options);

        if (result instanceof ConfigSubstitution) {
            throw new ConfigException.BugOrBroken(
                    "peek or peekPath returned an unresolved substitution");
        }

        return result;
    }

    private ConfigValue resolve(SubstitutionResolver resolver, SubstitutionExpression subst,
            int depth, ConfigResolveOptions options) {
        // First we look up the full path, which means relative to the
        // included file if we were not a root file
        ConfigValue result = findInObject(resolver.root(), resolver, subst.path(),
                depth, options);

        if (result == null) {
            // Then we want to check relative to the root file. We don't
            // want the prefix we were included at to be used when looking up
            // env variables either.
            Path unprefixed = subst.path().subPath(prefixLength);

            if (result == null && prefixLength > 0) {
                result = findInObject(resolver.root(), resolver, unprefixed, depth, options);
            }

            if (result == null && options.getUseSystemEnvironment()) {
                result = findInObject(ConfigImpl.envVariablesAsConfigObject(), null, unprefixed,
                        depth, options);
            }
        }

        return result;
    }

    private ConfigValue resolve(SubstitutionResolver resolver, int depth,
            ConfigResolveOptions options) {
        if (pieces.size() > 1) {
            // need to concat everything into a string
            StringBuilder sb = new StringBuilder();
            for (Object p : pieces) {
                if (p instanceof String) {
                    sb.append((String) p);
                } else {
                    SubstitutionExpression exp = (SubstitutionExpression) p;
                    ConfigValue v = resolve(resolver, exp, depth, options);

                    if (v == null) {
                        if (exp.optional()) {
                            // append nothing to StringBuilder
                        } else {
                            throw new ConfigException.UnresolvedSubstitution(origin(),
                                    exp.toString());
                        }
                    } else {
                        switch (v.valueType()) {
                        case LIST:
                        case OBJECT:
                            // cannot substitute lists and objects into strings
                            throw new ConfigException.WrongType(v.origin(), exp.path().render(),
                                    "not a list or object", v.valueType().name());
                        default:
                            sb.append(((AbstractConfigValue) v).transformToString());
                        }
                    }
                }
            }
            return new ConfigString(origin(), sb.toString());
        } else {
            if (!(pieces.get(0) instanceof SubstitutionExpression))
                throw new ConfigException.BugOrBroken(
                        "ConfigSubstitution should never contain a single String piece");
            SubstitutionExpression exp = (SubstitutionExpression) pieces.get(0);
            ConfigValue v = resolve(resolver, exp, depth, options);
            if (v == null && !exp.optional()) {
                throw new ConfigException.UnresolvedSubstitution(origin(), exp.toString());
            }
            return v;
        }
    }

    @Override
    AbstractConfigValue resolveSubstitutions(SubstitutionResolver resolver,
            int depth,
            ConfigResolveOptions options) {
        // only ConfigSubstitution adds to depth here, because the depth
        // is the substitution depth not the recursion depth
        AbstractConfigValue resolved = (AbstractConfigValue) resolve(resolver,
                depth + 1, options);
        return resolved;
    }

    @Override
    ResolveStatus resolveStatus() {
        return ResolveStatus.UNRESOLVED;
    }

    // when you graft a substitution into another object,
    // you have to prefix it with the location in that object
    // where you grafted it; but save prefixLength so
    // system property and env variable lookups don't get
    // broken.
    @Override
    ConfigSubstitution relativized(Path prefix) {
        List<Object> newPieces = new ArrayList<Object>();
        for (Object p : pieces) {
            if (p instanceof SubstitutionExpression) {
                SubstitutionExpression exp = (SubstitutionExpression) p;

                newPieces.add(exp.changePath(exp.path().prepend(prefix)));
            } else {
                newPieces.add(p);
            }
        }
        return new ConfigSubstitution(origin(), newPieces, prefixLength
                + prefix.length(), ignoresFallbacks);
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof ConfigSubstitution;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (other instanceof ConfigSubstitution) {
            return canEqual(other)
                    && this.pieces.equals(((ConfigSubstitution) other).pieces);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality
        return pieces.hashCode();
    }

    @Override
    protected void render(StringBuilder sb, int indent, boolean formatted) {
        for (Object p : pieces) {
            if (p instanceof SubstitutionExpression) {
                sb.append(p.toString());
            } else {
                sb.append(ConfigImplUtil.renderJsonString((String) p));
            }
        }
    }
}
