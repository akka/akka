/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil;

/*		 
 * Copyright (C) 2002-2016 Sebastiano Vigna
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */


/**
 * A function mapping keys into values.
 * <p>
 * <p>Instances of this class represent functions: the main difference with {@link java.util.Map}
 * is that functions do not in principle allow enumeration of their domain or range. The need for
 * this interface lies in the existence of several highly optimized implementations of
 * functions (e.g., minimal perfect hashes) which do not actually store their domain or range explicitly.
 * In case the domain is known, {@link #containsKey(Object)} can be used to perform membership queries.
 * <p>
 * <p>The choice of naming all methods exactly as in {@link java.util.Map} makes it possible
 * for all type-specific maps to extend type-specific functions (e.g., {@link akka.remote.artery.fastutil.ints.Int2IntMap}
 * extends {@link akka.remote.artery.fastutil.ints.Int2IntFunction}). However, {@link #size()} is allowed to return -1 to denote
 * that the number of keys is not available (e.g., in the case of a string hash function).
 * <p>
 * <p>Note that there is an {@link akka.remote.artery.fastutil.objects.Object2ObjectFunction} that
 * can also set its default return value.
 * <p>
 * <p><strong>Warning</strong>: Equality of functions is <em>not specified</em>
 * by contract, and it will usually be <em>by reference</em>, as there is no way to enumerate the keys
 * and establish whether two functions represent the same mathematical entity.
 *
 * @see java.util.Map
 */

public interface Function<K, V> {

  /**
   * Associates the specified value with the specified key in this function (optional operation).
   *
   * @param key   the key.
   * @param value the value.
   * @return the old value, or <code>null</code> if no value was present for the given key.
   * @see java.util.Map#put(Object, Object)
   */

  V put(K key, V value);

  /**
   * Returns the value associated by this function to the specified key.
   *
   * @param key the key.
   * @return the corresponding value, or <code>null</code> if no value was present for the given key.
   * @see java.util.Map#get(Object)
   */

  V get(Object key);

  /**
   * Returns true if this function contains a mapping for the specified key.
   * <p>
   * <p>Note that for some kind of functions (e.g., hashes) this method
   * will always return true.
   *
   * @param key the key.
   * @return true if this function associates a value to <code>key</code>.
   * @see java.util.Map#containsKey(Object)
   */

  boolean containsKey(Object key);

  /**
   * Removes this key and the associated value from this function if it is present (optional operation).
   *
   * @param key the key.
   * @return the old value, or <code>null</code> if no value was present for the given key.
   * @see java.util.Map#remove(Object)
   */

  V remove(Object key);

  /**
   * Returns the intended number of keys in this function, or -1 if no such number exists.
   * <p>
   * <p>Most function implementations will have some knowledge of the intended number of keys
   * in their domain. In some cases, however, this might not be possible.
   *
   * @return the intended number of keys in this function, or -1 if that number is not available.
   */
  int size();

  /**
   * Removes all associations from this function (optional operation).
   *
   * @see java.util.Map#clear()
   */

  void clear();

}
