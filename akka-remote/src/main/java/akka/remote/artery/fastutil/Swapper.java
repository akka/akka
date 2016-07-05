/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil;

public interface Swapper {
  /**
   * Swaps the data at the given positions.
   *
   * @param a the first position to swap.
   * @param b the second position to swap.
   */
  void swap(int a, int b);
}
