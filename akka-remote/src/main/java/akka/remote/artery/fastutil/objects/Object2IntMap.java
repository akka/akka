/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

/*
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


import java.util.Map;


import akka.remote.artery.fastutil.ints.IntCollection;

import akka.remote.artery.fastutil.objects.ObjectSet;
import akka.remote.artery.fastutil.objects.ObjectIterator;

public interface Object2IntMap<K> extends Object2IntFunction<K>, Map<K, Integer> {
  
  /**
   * An entry set providing fast iteration.
   * <p>
   * <p>In some cases (e.g., hash-based classes) iteration over an entry set requires the creation
   * of a large number of {@link java.util.Map.Entry} objects. Some <code>fastutil</code>
   * maps might return {@linkplain #entrySet() entry set} objects of type <code>FastEntrySet</code>: in this case, {@link #fastIterator() fastIterator()}
   * will return an iterator that is guaranteed not to create a large number of objects, <em>possibly
   * by returning always the same entry</em> (of course, mutated).
   */

  public interface FastEntrySet<K> extends ObjectSet<Object2IntMap.Entry<K>> {
    /**
     * Returns a fast iterator over this entry set; the iterator might return always the same entry object, suitably mutated.
     *
     * @return a fast iterator over this entry set; the iterator might return always the same {@link java.util.Map.Entry} object, suitably mutated.
     */
    public ObjectIterator<Object2IntMap.Entry<K>> fastIterator();
  }

  /**
   * Returns a set view of the mappings contained in this map.
   * <P>Note that this specification strengthens the one given in {@link Map#entrySet()}.
   *
   * @return a set view of the mappings contained in this map.
   * @see Map#entrySet()
   */

  ObjectSet<Map.Entry<K, Integer>> entrySet();

  /**
   * Returns a type-specific set view of the mappings contained in this map.
   * <p>
   * <p>This method is necessary because there is no inheritance along
   * type parameters: it is thus impossible to strengthen {@link #entrySet()}
   * so that it returns an {@link akka.remote.artery.fastutil.objects.ObjectSet}
   * of type-specific entries (the latter makes it possible to
   * access keys and values with type-specific methods).
   *
   * @return a type-specific set view of the mappings contained in this map.
   * @see #entrySet()
   */

  ObjectSet<Object2IntMap.Entry<K>> object2IntEntrySet();

  /**
   * Returns a set view of the keys contained in this map.
   * <P>Note that this specification strengthens the one given in {@link Map#keySet()}.
   *
   * @return a set view of the keys contained in this map.
   * @see Map#keySet()
   */

  ObjectSet<K> keySet();

  /**
   * Returns a set view of the values contained in this map.
   * <P>Note that this specification strengthens the one given in {@link Map#values()}.
   *
   * @return a set view of the values contained in this map.
   * @see Map#values()
   */

  IntCollection values();


  /**
   * @see Map#containsValue(Object)
   */

  boolean containsValue(int value);


  /**
   * A type-specific {@link java.util.Map.Entry}; provides some additional methods
   * that use polymorphism to avoid (un)boxing.
   *
   * @see java.util.Map.Entry
   */

  interface Entry<K> extends Map.Entry<K, Integer> {
    /**
     * {@inheritDoc}
     *
     * @deprecated Please use the corresponding type-specific method instead.
     */
    @Deprecated
    @Override
    Integer getValue();


    /**
     * @see java.util.Map.Entry#setValue(Object)
     */
    int setValue(int value);

    /**
     * @see java.util.Map.Entry#getValue()
     */
    int getIntValue();


  }
}

