/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import java.util.Collection;

/**
 * A type-specific {@link Collection}; provides some additional methods
 * that use polymorphism to avoid (un)boxing.
 * <p>
 * <P>Additionally, this class defines strengthens (again) {@link #iterator()} and defines
 * a slightly different semantics for {@link #toArray(Object[])}.
 *
 * @see Collection
 */

public interface ObjectCollection<K> extends Collection<K>, ObjectIterable<K> {

  /**
   * Returns a type-specific iterator on the elements of this collection.
   * <p>
   * <p>Note that this specification strengthens the one given in
   * {@link java.lang.Iterable#iterator()}, which was already
   * strengthened in the corresponding type-specific class,
   * but was weakened by the fact that this interface extends {@link Collection}.
   *
   * @return a type-specific iterator on the elements of this collection.
   */
  ObjectIterator<K> iterator();

  /**
   * Returns a type-specific iterator on this elements of this collection.
   *
   * @see #iterator()
   * @deprecated As of <code>fastutil</code> 5, replaced by {@link #iterator()}.
   */
  @Deprecated
  ObjectIterator<K> objectIterator();

  /**
   * Returns an containing the items of this collection;
   * the runtime type of the returned array is that of the specified array.
   * <p>
   * <p><strong>Warning</strong>: Note that, contrarily to {@link Collection#toArray(Object[])}, this
   * methods just writes all elements of this collection: no special
   * value will be added after the last one.
   *
   * @param a if this array is big enough, it will be used to store this collection.
   * @return a primitive type array containing the items of this collection.
   * @see Collection#toArray(Object[])
   */
  <T> T[] toArray(T[] a);
}

