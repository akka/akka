/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import akka.remote.artery.fastutil.BidirectionalIterator;

import akka.remote.artery.fastutil.objects.ObjectBidirectionalIterator;


/**
 * A type-specific bidirectional iterator; provides an additional method to avoid (un)boxing,
 * and the possibility to skip elements backwards.
 *
 * @see BidirectionalIterator
 */


public interface IntBidirectionalIterator extends IntIterator, ObjectBidirectionalIterator<Integer> {


  /**
   * Returns the previous element as a primitive type.
   *
   * @return the previous element in the iteration.
   * @see java.util.ListIterator#previous()
   */

  int previousInt();


  /**
   * Moves back for the given number of elements.
   * <p>
   * <P>The effect of this call is exactly the same as that of
   * calling {@link #previous()} for <code>n</code> times (possibly stopping
   * if {@link #hasPrevious()} becomes false).
   *
   * @param n the number of elements to skip back.
   * @return the number of elements actually skipped.
   * @see java.util.Iterator#next()
   */

  int back(int n);
}

