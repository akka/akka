/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import java.util.function.{ Function => JFunction }

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl
import akka.annotation.DoNotInherit
import akka.japi.function.Procedure

/**
 * A non thread safe mutable message buffer that can be used to buffer messages inside actors
 * and then unstash them.
 *
 * The buffer can hold at most the given `capacity` number of messages.
 *
 * Not for user extension.
 */
@DoNotInherit trait StashBuffer[T] {

  /**
   * Check if the message buffer is empty.
   *
   * @return if the buffer is empty
   */
  def isEmpty: Boolean

  /**
   * Check if the message buffer is not empty.
   *
   * @return if the buffer is not empty
   */
  def nonEmpty: Boolean

  /**
   * How many elements are in the message buffer.
   *
   * @return the number of elements in the message buffer
   */
  def size: Int

  /**
   * @return `true` if no more messages can be added, i.e. size equals the capacity of the stash buffer
   */
  def isFull: Boolean

  /**
   * Add one element to the end of the message buffer.
   *
   * [[StashOverflowException]] is thrown if the buffer [[StashBuffer#isFull]].
   *
   * @param message the message to buffer
   * @return this message buffer
   * @throws  `StashOverflowException` is thrown if the buffer [[StashBuffer#isFull]].
   */
  def stash(message: T): StashBuffer[T]

  /**
   * Return the first element of the message buffer without removing it.
   *
   * @return the first element or throws `NoSuchElementException` if the buffer is empty
   * @throws `NoSuchElementException` if the buffer is empty
   */
  def head: T

  /**
   * Iterate over all elements of the buffer and apply a function to each element,
   * without removing them.
   *
   * @param f the function to apply to each element
   */
  def forEach(f: Procedure[T]): Unit

  /**
   * Process all stashed messages with the `behavior` and the returned
   * [[Behavior]] from each processed message. The `StashBuffer` will be
   * empty after processing all messages, unless an exception is thrown
   * or messages are stashed while unstashing.
   *
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `StashBuffer`, but unprocessed messages remain.
   *
   * It's allowed to stash messages while unstashing. Those newly added
   * messages will not be processed by this call and have to be unstashed
   * in another call.
   *
   * The `behavior` passed to `unstashAll` must not be `unhandled`.
   */
  def unstashAll(behavior: Behavior[T]): Behavior[T]

  /**
   * Process `numberOfMessages` of the stashed messages with the `behavior`
   * and the returned [[Behavior]] from each processed message.
   *
   * The purpose of this method, compared to `unstashAll`, is to unstash a limited
   * number of messages and then send a message to `self` before continuing unstashing
   * more. That means that other new messages may arrive in-between and those must
   * be stashed to keep the original order of messages. To differentiate between
   * unstashed and new incoming messages the unstashed messages can be wrapped
   * in another message with the `wrap`.
   *
   * If an exception is thrown by processing a message a proceeding messages
   * and the message causing the exception have been removed from the
   * `StashBuffer`, but unprocessed messages remain.
   *
   * It's allowed to stash messages while unstashing. Those newly added
   * messages will not be processed by this call and have to be unstashed
   * in another call.
   *
   * The `behavior` passed to `unstash` must not be `unhandled`.
   */
  def unstash(behavior: Behavior[T], numberOfMessages: Int, wrap: JFunction[T, T]): Behavior[T]

}

/**
 * Is thrown when the size of the stash exceeds the capacity of the stash buffer.
 */
final class StashOverflowException(message: String) extends scaladsl.StashOverflowException(message)
