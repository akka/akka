/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
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

    // this is a list of String and Path where the Path
    // have to be resolved to values, then if there's more
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
    }

    @Override
    public ConfigValueType valueType() {
        throw new ConfigException.NotResolved(
                "tried to get value type on an unresolved substitution: "
                        + this);
    }

    @Override
    public Object unwrapped() {
        throw new ConfigException.NotResolved(
                "tried to unwrap an unresolved substitution: " + this);
    }

    @Override
    protected ConfigSubstitution newCopy(boolean ignoresFallbacks) {
        return new ConfigSubstitution(origin(), pieces, prefixLength, ignoresFallbacks);
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

    private ConfigValue resolve(SubstitutionResolver resolver, Path subst,
            int depth, ConfigResolveOptions options) {
        ConfigValue result = findInObject(resolver.root(), resolver, subst,
                depth, options);

        // when looking up system props and env variables,
        // we don't want the prefix that was added when
        // we were included in another file.
        Path unprefixed = subst.subPath(prefixLength);

        if (result == null && options.getUseSystemProperties()) {
            result = findInObject(ConfigImpl.systemPropertiesAsConfigObject(), null,
                    unprefixed, depth, options);
        }

        if (result == null && options.getUseSystemEnvironment()) {
                result = findInObject(ConfigImpl.envVariablesAsConfigObject(), null,
                    unprefixed, depth, options);
        }

        if (result == null) {
            result = new ConfigNull(origin());
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
                    ConfigValue v = resolve(resolver, (Path) p, depth, options);
                    switch (v.valueType()) {
                    case NULL:
                        // nothing; becomes empty string
                        break;
                    case LIST:
                    case OBJECT:
                        // cannot substitute lists and objects into strings
                        throw new ConfigException.WrongType(v.origin(),
                                ((Path) p).render(),
                                "not a list or object", v.valueType().name());
                    default:
                        sb.append(((AbstractConfigValue) v).transformToString());
                    }
                }
            }
            return new ConfigString(origin(), sb.toString());
        } else {
            if (!(pieces.get(0) instanceof Path))
                throw new ConfigException.BugOrBroken(
                        "ConfigSubstitution should never contain a single String piece");
            return resolve(resolver, (Path) pieces.get(0), depth, options);
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
            if (p instanceof Path) {
                newPieces.add(((Path) p).prepend(prefix));
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
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SUBST");
        sb.append("(");
        for (Object p : pieces) {
            sb.append(p.toString());
            sb.append(",");
        }
        sb.setLength(sb.length() - 1); // chop comma
        sb.append(")");
        return sb.toString();
    }
}
