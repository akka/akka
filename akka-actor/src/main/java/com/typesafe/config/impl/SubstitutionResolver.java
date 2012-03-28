/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.HashMap;
import java.util.Map;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.impl.AbstractConfigValue.NeedsFullResolve;
import com.typesafe.config.impl.AbstractConfigValue.NotPossibleToResolve;

/**
 * This exists because we have to memoize resolved substitutions as we go
 * through the config tree; otherwise we could end up creating multiple copies
 * of values or whole trees of values as we follow chains of substitutions.
 */
final class SubstitutionResolver {
    private final class MemoKey {
        MemoKey(AbstractConfigValue value, Path restrictToChildOrNull) {
            this.value = value;
            this.restrictToChildOrNull = restrictToChildOrNull;
        }

        final private AbstractConfigValue value;
        final private Path restrictToChildOrNull;

        @Override
        public final int hashCode() {
            int h = System.identityHashCode(value);
            if (restrictToChildOrNull != null) {
                return h + 41 * (41 + restrictToChildOrNull.hashCode());
            } else {
                return h;
            }
        }

        @Override
        public final boolean equals(Object other) {
            if (other instanceof MemoKey) {
                MemoKey o = (MemoKey) other;
                if (o.value != this.value)
                    return false;
                else if (o.restrictToChildOrNull == this.restrictToChildOrNull)
                    return true;
                else if (o.restrictToChildOrNull == null || this.restrictToChildOrNull == null)
                    return false;
                else
                    return o.restrictToChildOrNull.equals(this.restrictToChildOrNull);
            } else {
                return false;
            }
        }
    }

    final private AbstractConfigObject root;
    // note that we can resolve things to undefined (represented as Java null,
    // rather than ConfigNull) so this map can have null values.
    final private Map<MemoKey, AbstractConfigValue> memos;

    SubstitutionResolver(AbstractConfigObject root) {
        this.root = root;
        this.memos = new HashMap<MemoKey, AbstractConfigValue>();
    }

    AbstractConfigValue resolve(AbstractConfigValue original, int depth,
            ConfigResolveOptions options, Path restrictToChildOrNull) throws NotPossibleToResolve,
            NeedsFullResolve {

        // a fully-resolved (no restrictToChild) object can satisfy a request
        // for a restricted object, so always check that first.
        final MemoKey fullKey = new MemoKey(original, null);
        MemoKey restrictedKey = null;

        AbstractConfigValue cached = memos.get(fullKey);

        // but if there was no fully-resolved object cached, we'll only
        // compute the restrictToChild object so use a more limited
        // memo key
        if (cached == null && restrictToChildOrNull != null) {
            restrictedKey = new MemoKey(original, restrictToChildOrNull);
            cached = memos.get(restrictedKey);
        }

        if (cached != null) {
            return cached;
        } else {
            AbstractConfigValue resolved = original.resolveSubstitutions(this, depth, options,
                        restrictToChildOrNull);

            if (resolved == null || resolved.resolveStatus() == ResolveStatus.RESOLVED) {
                // if the resolved object is fully resolved by resolving only
                // the restrictToChildOrNull, then it can be cached under
                // fullKey since the child we were restricted to turned out to
                // be the only unresolved thing.
                memos.put(fullKey, resolved);
            } else {
                // if we have an unresolved object then either we did a partial
                // resolve restricted to a certain child, or it's a bug.
                if (restrictToChildOrNull == null) {
                    throw new ConfigException.BugOrBroken(
                            "resolveSubstitutions() did not give us a resolved object");
                } else {
                    if (restrictedKey == null) {
                        throw new ConfigException.BugOrBroken(
                                "restrictedKey should not be null here");
                    }
                    memos.put(restrictedKey, resolved);
                }
            }

            return resolved;
        }
    }

    AbstractConfigObject root() {
        return this.root;
    }

    static AbstractConfigValue resolve(AbstractConfigValue value, AbstractConfigObject root,
            ConfigResolveOptions options, Path restrictToChildOrNull) throws NotPossibleToResolve,
            NeedsFullResolve {
        SubstitutionResolver resolver = new SubstitutionResolver(root);
        return resolver.resolve(value, 0, options, restrictToChildOrNull);
    }

    static AbstractConfigValue resolveWithExternalExceptions(AbstractConfigValue value,
            AbstractConfigObject root, ConfigResolveOptions options) {
        SubstitutionResolver resolver = new SubstitutionResolver(root);
        try {
            return resolver.resolve(value, 0, options, null /* restrictToChild */);
        } catch (NotPossibleToResolve e) {
            throw e.exportException(value.origin(), null);
        } catch (NeedsFullResolve e) {
            throw new ConfigException.NotResolved(value.origin().description()
                    + ": Must resolve() config object before use", e);
        }
    }
}
