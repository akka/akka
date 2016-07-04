/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

/**
 * An abstract class facilitating the creation of type-specific {@linkplain java.util.ListIterator list iterators}.
 * <p>
 * <P>This class provides trivial type-specific implementations of {@link
 * java.util.ListIterator#set(Object) set()} and {@link java.util.ListIterator#add(Object) add()} which
 * throw an {@link UnsupportedOperationException}. For primitive types, it also
 * provides a trivial implementation of {@link java.util.ListIterator#set(Object) set()} and {@link
 * java.util.ListIterator#add(Object) add()} that just invokes the type-specific one.
 *
 * @see java.util.ListIterator
 */

public abstract class AbstractObjectListIterator<K> extends AbstractObjectBidirectionalIterator<K> implements ObjectListIterator<K> {

  protected AbstractObjectListIterator() {
  }

  /**
   * This method just throws an  {@link UnsupportedOperationException}.
   */
  public void set(K k) {
    throw new UnsupportedOperationException();
  }

  /**
   * This method just throws an  {@link UnsupportedOperationException}.
   */
  public void add(K k) {
    throw new UnsupportedOperationException();
  }
}

