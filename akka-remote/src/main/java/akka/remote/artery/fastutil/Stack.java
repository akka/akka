/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil;

import java.util.NoSuchElementException;

/**
 * A stack.
 * <p>
 * <P>A stack must provide the classical {@link #push(Object)} and
 * {@link #pop()} operations, but may be also <em>peekable</em>
 * to some extent: it may provide just the {@link #top()} function,
 * or even a more powerful {@link #peek(int)} method that provides
 * access to all elements on the stack (indexed from the top, which
 * has index 0).
 */

public interface Stack<K> {

  /**
   * Pushes the given object on the stack.
   *
   * @param o the object that will become the new top of the stack.
   */

  void push(K o);

  /**
   * Pops the top off the stack.
   *
   * @return the top of the stack.
   * @throws NoSuchElementException if the stack is empty.
   */

  K pop();

  /**
   * Checks whether the stack is empty.
   *
   * @return true if the stack is empty.
   */

  boolean isEmpty();

  /**
   * Peeks at the top of the stack (optional operation).
   *
   * @return the top of the stack.
   * @throws NoSuchElementException if the stack is empty.
   */

  K top();

  /**
   * Peeks at an element on the stack (optional operation).
   *
   * @param i an index from the stop of the stack (0 represents the top).
   * @return the <code>i</code>-th element on the stack.
   * @throws IndexOutOfBoundsException if the designated element does not exist..
   */

  K peek(int i);

}
