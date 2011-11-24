/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

/**
 * This is a marker for types that can be merged as a fallback into a Config or
 * a ConfigValue. Both Config and ConfigValue are mergeable.
 */
public interface ConfigMergeable {
    /**
     * Converts the mergeable to a ConfigValue to be merged.
     *
     * @return
     */
    ConfigValue toValue();

    /**
     * Returns a new value computed by merging this value with another, with
     * keys in this value "winning" over the other one. Only ConfigObject and
     * Config instances do anything in this method (they need to merge the
     * fallback keys into themselves). All other values just return the original
     * value, since they automatically override any fallback.
     * 
     * The semantics of merging are described in
     * https://github.com/havocp/config/blob/master/HOCON.md
     * 
     * Note that objects do not merge "across" non-objects; if you do
     * <code>object.withFallback(nonObject).withFallback(otherObject)</code>,
     * then <code>otherObject</code> will simply be ignored. This is an
     * intentional part of how merging works. Both non-objects, and any object
     * which has fallen back to a non-object, block subsequent fallbacks.
     * 
     * @param other
     *            an object whose keys should be used if the keys are not
     *            present in this one
     * @return a new object (or the original one, if the fallback doesn't get
     *         used)
     */
    ConfigMergeable withFallback(ConfigMergeable other);
}
