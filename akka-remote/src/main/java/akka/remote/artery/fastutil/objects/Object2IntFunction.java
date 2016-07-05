/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import akka.remote.artery.fastutil.Function;

/**
 * A type-specific {@link Function}; provides some additional methods that use polymorphism to avoid (un)boxing.
 * <p>
 * <P>Type-specific versions of <code>get()</code>, <code>put()</code> and
 * <code>remove()</code> cannot rely on <code>null</code> to denote absence of
 * a key.  Rather, they return a {@linkplain #defaultReturnValue() default
 * return value}, which is set to 0 cast to the return type (<code>false</code>
 * for booleans) at creation, but can be changed using the
 * <code>defaultReturnValue()</code> method.
 * <p>
 * <P>For uniformity reasons, even maps returning objects implement the default
 * return value (of course, in this case the default return value is
 * initialized to <code>null</code>).
 * <p>
 * <P><strong>Warning:</strong> to fall in line as much as possible with the
 * {@linkplain java.util.Map standard map interface}, it is strongly suggested
 * that standard versions of <code>get()</code>, <code>put()</code> and
 * <code>remove()</code> for maps with primitive-type values <em>return
 * <code>null</code> to denote missing keys</em> rather than wrap the default
 * return value in an object (of course, for maps with object keys and values
 * this is not possible, as there is no type-specific version).
 *
 * @see Function
 */

public interface Object2IntFunction<K> extends Function<K, Integer> {


  /**
   * Adds a pair to the map.
   *
   * @param key   the key.
   * @param value the value.
   * @return the old value, or the {@linkplain #defaultReturnValue() default return value} if no value was present for the given key.
   * @see Function#put(Object, Object)
   */

  int put(K key, int value);

  /**
   * Returns the value to which the given key is mapped.
   *
   * @param key the key.
   * @return the corresponding value, or the {@linkplain #defaultReturnValue() default return value} if no value was present for the given key.
   * @see Function#get(Object)
   */

  int getInt(Object key);

  /**
   * Removes the mapping with the given key.
   *
   * @param key the key.
   * @return the old value, or the {@linkplain #defaultReturnValue() default return value} if no value was present for the given key.
   * @see Function#remove(Object)
   */

  int removeInt(Object key);

  /**
   * Sets the default return value.
   * <p>
   * This value must be returned by type-specific versions of
   * <code>get()</code>, <code>put()</code> and <code>remove()</code> to
   * denote that the map does not contain the specified key. It must be
   * 0/<code>false</code>/<code>null</code> by default.
   *
   * @param rv the new default return value.
   * @see #defaultReturnValue()
   */

  void defaultReturnValue(int rv);


  /**
   * Gets the default return value.
   *
   * @return the current default return value.
   */

  int defaultReturnValue();

}

