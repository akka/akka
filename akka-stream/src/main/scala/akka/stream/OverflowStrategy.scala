/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

/**
 * Represents a strategy that decides how to deal with a buffer that is full but is about to receive a new element.
 */
sealed abstract class OverflowStrategy

object OverflowStrategy {

  /**
   * INTERNAL API
   */
  private[akka] final case object DropHead extends OverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] final case object DropTail extends OverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] final case object DropBuffer extends OverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] final case object Backpressure extends OverflowStrategy

  /**
   * If the buffer is full when a new element arrives, drops the oldest element from the buffer to make space for
   * the new element.
   */
  def dropHead: OverflowStrategy = DropHead

  /**
   * If the buffer is full when a new element arrives, drops the youngest element from the buffer to make space for
   * the new element.
   */
  def dropTail: OverflowStrategy = DropTail

  /**
   * If the buffer is full when a new element arrives, drops all the buffered elements to make space for the new element.
   */
  def dropBuffer: OverflowStrategy = DropBuffer

  /**
   * If the buffer is full when a new element is available this strategy backpressures the upstream producer until
   * space becomes available in the buffer.
   */
  def backpressure: OverflowStrategy = Backpressure
}
