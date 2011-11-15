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
     * Prefer <code>withFallbacks()</code>, listing all your fallbacks at once,
     * over this method.
     *
     * <i>When using this method, there is an easy way to write a wrong
     * loop.</i> Even if you don't loop, it's easy to do the equivalent wrong
     * thing.
     *
     * <code>
     *   // WRONG
     *   for (ConfigMergeable fallback : fallbacks) {
     *       // DO NOT DO THIS
     *       result = result.withFallback(fallback);
     *   }
     * </code>
     *
     * This is wrong because when <code>result</code> is an object and
     * <code>fallback</code> is a non-object,
     * <code>result.withFallback(fallback)</code> returns an object. Then if
     * there are more objects, they are merged into that object. But the correct
     * semantics are that a non-object will block merging any more objects later
     * in the list. To get it right, you need to iterate backward. Simplest
     * solution: prefer <code>withFallbacks()</code> which is harder to get
     * wrong, and merge all your fallbacks in one call to
     * <code>withFallbacks()</code>.
     *
     * @param other
     *            an object whose keys should be used if the keys are not
     *            present in this one
     * @return a new object (or the original one, if the fallback doesn't get
     *         used)
     */
    ConfigMergeable withFallback(ConfigMergeable other);

    /**
     * Convenience method just calls withFallback() on each of the values;
     * earlier values in the list win over later ones. The semantics of merging
     * are described in https://github.com/havocp/config/blob/master/HOCON.md
     *
     * @param fallbacks
     * @return a version of the object with the requested fallbacks merged in
     */
    ConfigMergeable withFallbacks(ConfigMergeable... others);
}
