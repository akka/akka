/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl

import akka.actor.typed.ActorRef
import akka.annotation.DoNotInherit
import akka.testkit.typed.internal.TestInboxImpl

import scala.collection.JavaConverters._
import scala.collection.immutable

object TestInbox {
  def create[T](name: String): TestInbox[T] = new TestInboxImpl(name)
  def create[T](): TestInbox[T] = new TestInboxImpl[T]("inbox")
}

/**
 * Utility for use as an [[ActorRef]] when *synchronously* testing [[akka.actor.typed.Behavior]]
 * with [[akka.testkit.typed.javadsl.BehaviorTestKit]].
 *
 * If you plan to use a real [[akka.actor.typed.ActorSystem]] then use [[akka.testkit.typed.javadsl.TestProbe]]
 * for asynchronous testing.
 *
 * Use `TestInbox.create` factory methods to create instances
 *
 * Not for user extension
 */
@DoNotInherit
abstract class TestInbox[T] {
  /**
   * The actor ref of the inbox
   */
  def getRef(): ActorRef[T]

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
  def getAllReceived(): java.util.List[T] = internalReceiveAll().asJava

  protected def internalReceiveAll(): immutable.Seq[T]

  def hasMessages: Boolean

  // TODO expectNoMsg etc
}
