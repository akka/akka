/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import OverflowStrategies._
import akka.annotation.DoNotInherit
import akka.event.Logging
import akka.event.Logging.LogLevel

/**
 * Represents a strategy that decides how to deal with a buffer of time based operator
 * that is full but is about to receive a new element.
 */
@DoNotInherit
sealed abstract class DelayOverflowStrategy extends Serializable

final case class BufferOverflowException(msg: String) extends RuntimeException(msg)

/**
 * Represents a strategy that decides how to deal with a buffer that is full but is
 * about to receive a new element.
 */
@DoNotInherit
sealed abstract class OverflowStrategy extends DelayOverflowStrategy

private[akka] object OverflowStrategies {
  /**
   * INTERNAL API
   */
  private[akka] case class DropHead(logLevel: LogLevel) extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case class DropTail(logLevel: LogLevel) extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case class DropBuffer(logLevel: LogLevel) extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case class DropNew(logLevel: LogLevel) extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case class Backpressure(logLevel: LogLevel) extends OverflowStrategy
  /**
   * INTERNAL API
   */
  private[akka] case class Fail(logLevel: LogLevel) extends OverflowStrategy
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
  def dropHead: OverflowStrategy = DropHead(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops the oldest element from the buffer to make space for
   * the new element.
   */
  def dropHead(logLevel: LogLevel): OverflowStrategy = DropHead(logLevel)

  /**
   * If the buffer is full when a new element arrives, drops the youngest element from the buffer to make space for
   * the new element.
   */
  def dropTail: OverflowStrategy = DropTail(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops the youngest element from the buffer to make space for
   * the new element.
   */
  def dropTail(logLevel: LogLevel): OverflowStrategy = DropTail(logLevel)

  /**
   * If the buffer is full when a new element arrives, drops all the buffered elements to make space for the new element.
   */
  def dropBuffer: OverflowStrategy = DropBuffer(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops all the buffered elements to make space for the new element.
   */
  def dropBuffer(logLevel: LogLevel): OverflowStrategy = DropBuffer(logLevel)

  /**
   * If the buffer is full when a new element arrives, drops the new element.
   */
  def dropNew: OverflowStrategy = DropNew(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops the new element.
   */
  def dropNew(logLevel: LogLevel = Logging.DebugLevel): OverflowStrategy = DropNew(logLevel)

  /**
   * If the buffer is full when a new element is available this strategy backpressures the upstream publisher until
   * space becomes available in the buffer.
   */
  def backpressure: OverflowStrategy = Backpressure(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element is available this strategy backpressures the upstream publisher until
   * space becomes available in the buffer.
   */
  def backpressure(logLevel: LogLevel): OverflowStrategy = Backpressure(logLevel)

  /**
   * If the buffer is full when a new element is available this strategy completes the stream with failure.
   */
  def fail: OverflowStrategy = Fail(Logging.ErrorLevel)

  /**
   * If the buffer is full when a new element is available this strategy completes the stream with failure.
   */
  def fail(logLevel: LogLevel): OverflowStrategy = Fail(logLevel)
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
  def dropHead: DelayOverflowStrategy = DropHead(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops the youngest element from the buffer to make space for
   * the new element.
   */
  def dropTail: DelayOverflowStrategy = DropTail(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops all the buffered elements to make space for the new element.
   */
  def dropBuffer: DelayOverflowStrategy = DropBuffer(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element arrives, drops the new element.
   */
  def dropNew: DelayOverflowStrategy = DropNew(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element is available this strategy backpressures the upstream publisher until
   * space becomes available in the buffer.
   */
  def backpressure: DelayOverflowStrategy = Backpressure(Logging.DebugLevel)

  /**
   * If the buffer is full when a new element is available this strategy completes the stream with failure.
   */
  def fail: DelayOverflowStrategy = Fail(Logging.ErrorLevel)
}
