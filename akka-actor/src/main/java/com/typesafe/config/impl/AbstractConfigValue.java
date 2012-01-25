/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigMergeable;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;

/**
 *
 * Trying very hard to avoid a parent reference in config values; when you have
 * a tree like this, the availability of parent() tends to result in a lot of
 * improperly-factored and non-modular code. Please don't add parent().
 *
 */
abstract class AbstractConfigValue implements ConfigValue, MergeableValue {

    final private SimpleConfigOrigin origin;

    AbstractConfigValue(ConfigOrigin origin) {
        this.origin = (SimpleConfigOrigin) origin;
    }

    @Override
    public SimpleConfigOrigin origin() {
        return this.origin;
    }

    /**
     * Called only by SubstitutionResolver object.
     *
     * @param resolver
     *            the resolver doing the resolving
     * @param depth
     *            the number of substitutions followed in resolving the current
     *            one
     * @param options
     *            whether to look at system props and env vars
     * @return a new value if there were changes, or this if no changes
     */
    AbstractConfigValue resolveSubstitutions(SubstitutionResolver resolver,
            int depth,
            ConfigResolveOptions options) {
        return this;
    }

    ResolveStatus resolveStatus() {
        return ResolveStatus.RESOLVED;
    }

    /**
     * This is used when including one file in another; the included file is
     * relativized to the path it's included into in the parent file. The point
     * is that if you include a file at foo.bar in the parent, and the included
     * file as a substitution ${a.b.c}, the included substitution now needs to
     * be ${foo.bar.a.b.c} because we resolve substitutions globally only after
     * parsing everything.
     *
     * @param prefix
     * @return value relativized to the given path or the same value if nothing
     *         to do
     */
    AbstractConfigValue relativized(Path prefix) {
        return this;
    }

    protected interface Modifier {
        AbstractConfigValue modifyChild(AbstractConfigValue v);
    }

    @Override
    public AbstractConfigValue toFallbackValue() {
        return this;
    }

    protected abstract AbstractConfigValue newCopy(boolean ignoresFallbacks, ConfigOrigin origin);

    // this is virtualized rather than a field because only some subclasses
    // really need to store the boolean, and they may be able to pack it
    // with another boolean to save space.
    protected boolean ignoresFallbacks() {
        return true;
    }

    private ConfigException badMergeException() {
        if (ignoresFallbacks())
            throw new ConfigException.BugOrBroken(
                    "method should not have been called with ignoresFallbacks=true"
                            + getClass().getSimpleName());
        else
            throw new ConfigException.BugOrBroken("should override this in "
                    + getClass().getSimpleName());
    }

    protected AbstractConfigValue mergedWithTheUnmergeable(Unmergeable fallback) {
        throw badMergeException();
    }

    protected AbstractConfigValue mergedWithObject(AbstractConfigObject fallback) {
        throw badMergeException();
    }

    protected AbstractConfigValue mergedWithNonObject(AbstractConfigValue fallback) {
        // falling back to a non-object doesn't merge anything, and also
        // prohibits merging any objects that we fall back to later.
        // so we have to switch to ignoresFallbacks mode.
        return newCopy(true /* ignoresFallbacks */, origin);
    }

    public AbstractConfigValue withOrigin(ConfigOrigin origin) {
        if (this.origin == origin)
            return this;
        else
            return newCopy(ignoresFallbacks(), origin);
    }

    @Override
    public AbstractConfigValue withFallback(ConfigMergeable mergeable) {
        if (ignoresFallbacks()) {
            return this;
        } else {
            ConfigValue other = ((MergeableValue) mergeable).toFallbackValue();

            if (other instanceof Unmergeable) {
                return mergedWithTheUnmergeable((Unmergeable) other);
            } else if (other instanceof AbstractConfigObject) {
                AbstractConfigObject fallback = (AbstractConfigObject) other;
                if (fallback.resolveStatus() == ResolveStatus.RESOLVED && fallback.isEmpty()) {
                    if (fallback.ignoresFallbacks())
                        return newCopy(true /* ignoresFallbacks */, origin);
                    else
                        return this;
                } else {
                    return mergedWithObject((AbstractConfigObject) other);
                }
            } else {
                return mergedWithNonObject((AbstractConfigValue) other);
            }
        }
    }

    protected boolean canEqual(Object other) {
        return other instanceof ConfigValue;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (other instanceof ConfigValue) {
            return canEqual(other)
                    && (this.valueType() ==
                            ((ConfigValue) other).valueType())
                    && ConfigImplUtil.equalsHandlingNull(this.unwrapped(),
                            ((ConfigValue) other).unwrapped());
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality
        Object o = this.unwrapped();
        if (o == null)
            return 0;
        else
            return o.hashCode();
    }

    @Override
    public final String toString() {
        StringBuilder sb = new StringBuilder();
        render(sb, 0, null /* atKey */, false /* formatted */);
        return getClass().getSimpleName() + "(" + sb.toString() + ")";
    }

    protected static void indent(StringBuilder sb, int indent) {
        int remaining = indent;
        while (remaining > 0) {
            sb.append("    ");
            --remaining;
        }
    }

    protected void render(StringBuilder sb, int indent, String atKey, boolean formatted) {
        if (atKey != null) {
            sb.append(ConfigImplUtil.renderJsonString(atKey));
            sb.append(" : ");
        }
        render(sb, indent, formatted);
    }

    protected void render(StringBuilder sb, int indent, boolean formatted) {
        Object u = unwrapped();
        sb.append(u.toString());
    }


    @Override
    public final String render() {
        StringBuilder sb = new StringBuilder();
        render(sb, 0, null, true /* formatted */);
        return sb.toString();
    }

    // toString() is a debugging-oriented string but this is defined
    // to create a string that would parse back to the value in JSON.
    // It only works for primitive values (that would be a single token)
    // which are auto-converted to strings when concatenating with
    // other strings or by the DefaultTransformer.
    String transformToString() {
        return null;
    }
}
