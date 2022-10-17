/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import scala.collection.immutable

import akka.actor.testkit.typed.internal.TestInboxImpl
import akka.actor.typed.ActorRef
import akka.annotation.{ ApiMayChange, DoNotInherit }

@ApiMayChange
object TestInbox {
  def apply[T](name: String = "inbox"): TestInbox[T] = TestInboxImpl(name)
  
  private[akka] val address = TestBoxImpl.address
}

/**
 * Utility for use as an [[ActorRef]] when *synchronously* testing [[akka.actor.typed.Behavior]]
 * with [[akka.actor.testkit.typed.javadsl.BehaviorTestKit]].
 *
 * If you plan to use a real [[akka.actor.typed.ActorSystem]] then use [[akka.actor.testkit.typed.javadsl.TestProbe]]
 * for asynchronous testing.
 *
 * Use factory `apply` in companion to create instances
 *
 * Not for user extension
 */
@DoNotInherit
@ApiMayChange
trait TestInbox[T] {

  /**
   * The actor ref of the inbox
   */
  def ref: ActorRef[T]

  /**
   * Get and remove the oldest message
   */
  def receiveMessage(): T

  /**
   * Assert and remove the the oldest message.
   */
  def expectMessage(expectedMessage: T): TestInbox[T]

  /**
   * Collect all messages in the inbox and clear it out
   */
  def receiveAll(): immutable.Seq[T] = internalReceiveAll()

  protected def internalReceiveAll(): immutable.Seq[T]

  def hasMessages: Boolean

  // TODO expectNoMsg etc
}
