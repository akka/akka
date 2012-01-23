/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.IdentityHashMap;
import java.util.Map;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigResolveOptions;

/**
 * This exists because we have to memoize resolved substitutions as we go
 * through the config tree; otherwise we could end up creating multiple copies
 * of values or whole trees of values as we follow chains of substitutions.
 */
final class SubstitutionResolver {
    final private AbstractConfigObject root;
    // note that we can resolve things to undefined (represented as Java null,
    // rather than ConfigNull) so this map can have null values.
    final private Map<AbstractConfigValue, AbstractConfigValue> memos;

    SubstitutionResolver(AbstractConfigObject root) {
        this.root = root;
        // note: the memoization is by object identity, not object value
        this.memos = new IdentityHashMap<AbstractConfigValue, AbstractConfigValue>();
    }

    AbstractConfigValue resolve(AbstractConfigValue original, int depth,
            ConfigResolveOptions options) {
        if (memos.containsKey(original)) {
            return memos.get(original);
        } else {
            AbstractConfigValue resolved = original.resolveSubstitutions(this,
                    depth, options);
            if (resolved != null) {
                if (resolved.resolveStatus() != ResolveStatus.RESOLVED)
                    throw new ConfigException.BugOrBroken(
                            "resolveSubstitutions() did not give us a resolved object");
            }
            memos.put(original, resolved);
            return resolved;
        }
    }

    AbstractConfigObject root() {
        return this.root;
    }

    static AbstractConfigValue resolve(AbstractConfigValue value,
            AbstractConfigObject root, ConfigResolveOptions options) {
        SubstitutionResolver resolver = new SubstitutionResolver(root);
        return resolver.resolve(value, 0, options);
    }
}
