/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.objects;

/**
 * An abstract class facilitating the creation of type-specific iterators.
 * <p>
 * <P>To create a type-specific iterator you need both a method returning the
 * next element as primitive type and a method returning the next element as an
 * object. However, if you inherit from this class you need just one (anyone).
 * <p>
 * <P>This class implements also a trivial version of {@link #skip(int)} that uses
 * type-specific methods; moreover, {@link #remove()} will throw an {@link
 * UnsupportedOperationException}.
 *
 * @see java.util.Iterator
 */

public abstract class AbstractObjectIterator<K> implements ObjectIterator<K> {

  protected AbstractObjectIterator() {
  }

  /**
   * This method just throws an  {@link UnsupportedOperationException}.
   */
  public void remove() {
    throw new UnsupportedOperationException();
  }

  /**
   * This method just iterates the type-specific version of {@link #next()} for at most
   * <code>n</code> times, stopping if {@link #hasNext()} becomes false.
   */

  public int skip(final int n) {
    int i = n;
    while (i-- != 0 && hasNext()) {
      next();
    }
    return n - i - 1;
  }
}

