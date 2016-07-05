/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

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

public abstract class AbstractIntListIterator extends AbstractIntBidirectionalIterator implements IntListIterator {

  protected AbstractIntListIterator() {
  }


  /**
   * Delegates to the corresponding type-specific method.
   */
  public void set(Integer ok) {
    set(ok.intValue());
  }

  /**
   * Delegates to the corresponding type-specific method.
   */
  public void add(Integer ok) {
    add(ok.intValue());
  }


  /**
   * This method just throws an  {@link UnsupportedOperationException}.
   */
  public void set(int k) {
    throw new UnsupportedOperationException();
  }

  /**
   * This method just throws an  {@link UnsupportedOperationException}.
   */
  public void add(int k) {
    throw new UnsupportedOperationException();
  }
}

