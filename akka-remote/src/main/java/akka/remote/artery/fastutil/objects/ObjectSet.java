/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import java.util.Set;

/**
 * A type-specific {@link Set}; provides some additional methods that use polymorphism to avoid (un)boxing.
 * <p>
 * <P>Additionally, this interface strengthens (again) {@link #iterator()}.
 *
 * @see Set
 */

public interface ObjectSet<K> extends ObjectCollection<K>, Set<K> {

  /**
   * Returns a type-specific iterator on the elements of this set.
   * <p>
   * <p>Note that this specification strengthens the one given in {@link java.lang.Iterable#iterator()},
   * which was already strengthened in the corresponding type-specific class,
   * but was weakened by the fact that this interface extends {@link Set}.
   *
   * @return a type-specific iterator on the elements of this set.
   */
  ObjectIterator<K> iterator();

  /**
   * Removes an element from this set.
   * <p>
   * <p>Note that the corresponding method of the type-specific collection is <code>rem()</code>.
   * This unfortunate situation is caused by the clash
   * with the similarly named index-based method in the {@link java.util.List} interface.
   *
   * @see java.util.Collection#remove(Object)
   */
  public boolean remove(Object k);
}

