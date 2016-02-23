/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import OverflowStrategies._

/**
 * Represents a strategy that decides how to deal with a buffer of time based stage
 * that is full but is about to receive a new element.
 */
sealed abstract class DelayOverflowStrategy extends Serializable

final case class BufferOverflowException(msg: String) extends RuntimeException(msg)
/**
 * Represents a strategy that decides how to deal with a buffer that is full but is
 * about to receive a new element.
 */
sealed abstract class OverflowStrategy extends DelayOverflowStrategy

private[akka] object OverflowStrategies {
  /**
   * INTERNAL API
   */
  private[akka] case object DropHead extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case object DropTail extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case object DropBuffer extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case object DropNew extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case object Backpressure extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case object Fail extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case object EmitEarly extends DelayOverflowStrategy
}

object OverflowStrategy {
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
   * If the buffer is full when a new element arrives, drops the new element.
   */
  def dropNew: OverflowStrategy = DropNew

  /**
   * If the buffer is full when a new element is available this strategy backpressures the upstream publisher until
   * space becomes available in the buffer.
   */
  def backpressure: OverflowStrategy = Backpressure

  /**
   * If the buffer is full when a new element is available this strategy completes the stream with failure.
   */
  def fail: OverflowStrategy = Fail
}

object DelayOverflowStrategy {
  /**
   * If the buffer is full when a new element is available this strategy send next element downstream without waiting
   */
  def emitEarly: DelayOverflowStrategy = EmitEarly

  /**
   * If the buffer is full when a new element arrives, drops the oldest element from the buffer to make space for
   * the new element.
   */
  def dropHead: DelayOverflowStrategy = DropHead

  /**
   * If the buffer is full when a new element arrives, drops the youngest element from the buffer to make space for
   * the new element.
   */
  def dropTail: DelayOverflowStrategy = DropTail

  /**
   * If the buffer is full when a new element arrives, drops all the buffered elements to make space for the new element.
   */
  def dropBuffer: DelayOverflowStrategy = DropBuffer

  /**
   * If the buffer is full when a new element arrives, drops the new element.
   */
  def dropNew: DelayOverflowStrategy = DropNew

  /**
   * If the buffer is full when a new element is available this strategy backpressures the upstream publisher until
   * space becomes available in the buffer.
   */
  def backpressure: DelayOverflowStrategy = Backpressure

  /**
   * If the buffer is full when a new element is available this strategy completes the stream with failure.
   */
  def fail: DelayOverflowStrategy = Fail
}