/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

import akka.remote.artery.fastutil.Stack;

/**
 * A type-specific {@link Stack}; provides some additional methods that use polymorphism to avoid (un)boxing.
 */

public interface IntStack extends Stack<Integer> {

  /**
   * @see Stack#push(Object)
   */

  void push(int k);

  /**
   * @see Stack#pop()
   */
  int popInt();

  /**
   * @see Stack#top()
   */

  int topInt();

  /**
   * @see Stack#peek(int)
   */

  int peekInt(int i);

}

