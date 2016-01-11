/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

/**
 * Represents a strategy that decides how to deal with a buffer that is full but is about to receive a new element.
 */
sealed abstract class OverflowStrategy extends Serializable
sealed trait DelayOverflowStrategy extends Serializable

private[akka] trait BaseOverflowStrategy {

  /**
   * INTERNAL API
   */
  private[akka] case object DropHead extends OverflowStrategy with DelayOverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] case object DropTail extends OverflowStrategy with DelayOverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] case object DropBuffer extends OverflowStrategy with DelayOverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] case object DropNew extends OverflowStrategy with DelayOverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] case object Backpressure extends OverflowStrategy with DelayOverflowStrategy

  /**
   * INTERNAL API
   */
  private[akka] case object Fail extends OverflowStrategy with DelayOverflowStrategy {
    final case class BufferOverflowException(msg: String) extends RuntimeException(msg)
  }
}

object OverflowStrategy extends BaseOverflowStrategy {
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

object DelayOverflowStrategy extends BaseOverflowStrategy {
  /**
   * INTERNAL API
   */
  private[akka] case object EmitEarly extends DelayOverflowStrategy

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