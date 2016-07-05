/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.ints;

import java.util.Comparator;

/**
 * A type-specific {@link Comparator}; provides methods to compare two primitive types both as objects
 * and as primitive types.
 * <p>
 * <P>Note that <code>fastutil</code> provides a corresponding abstract class that
 * can be used to implement this interface just by specifying the type-specific
 * comparator.
 *
 * @see Comparator
 */

public interface IntComparator extends Comparator<Integer> {

  /**
   * Compares the given primitive types.
   *
   * @return A positive integer, zero, or a negative integer if the first
   * argument is greater than, equal to, or smaller than, respectively, the
   * second one.
   * @see java.util.Comparator
   */

  public int compare(int k1, int k2);
}

