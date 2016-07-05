/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import java.util.Iterator;

/**
 * A type-specific {@link Iterator}; provides an additional method to avoid (un)boxing, and
 * the possibility to skip elements.
 *
 * @see Iterator
 */

public interface IntIterator extends Iterator<Integer> {


  /**
   * Returns the next element as a primitive type.
   *
   * @return the next element in the iteration.
   * @see Iterator#next()
   */

  int nextInt();


  /**
   * Skips the given number of elements.
   * <p>
   * <P>The effect of this call is exactly the same as that of
   * calling {@link #next()} for <code>n</code> times (possibly stopping
   * if {@link #hasNext()} becomes false).
   *
   * @param n the number of elements to skip.
   * @return the number of elements actually skipped.
   * @see Iterator#next()
   */

  int skip(int n);
}

