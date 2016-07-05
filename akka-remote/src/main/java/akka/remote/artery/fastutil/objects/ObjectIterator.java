/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.objects;

import java.util.Iterator;

/**
 * A type-specific {@link Iterator}; provides an additional method to avoid (un)boxing, and
 * the possibility to skip elements.
 *
 * @see Iterator
 */

public interface ObjectIterator<K> extends Iterator<K> {
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

