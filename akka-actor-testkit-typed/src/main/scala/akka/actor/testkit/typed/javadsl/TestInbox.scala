/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl

import java.util.concurrent.ThreadLocalRandom

import scala.collection.immutable

import akka.actor.testkit.typed.internal.TestInboxImpl
import akka.actor.typed.ActorRef
import akka.annotation.DoNotInherit
import akka.util.ccompat.JavaConverters._

object TestInbox {
  import akka.actor.testkit.typed.scaladsl.TestInbox.address

  def create[T](name: String): TestInbox[T] = {
    val uid = ThreadLocalRandom.current().nextInt()
    new TestInboxImpl((address / name).withUid(uid))
  }
  def create[T](): TestInbox[T] = {
    val uid = ThreadLocalRandom.current().nextInt()
    new TestInboxImpl((address / "inbox").withUid(uid))
  }
}

/**
 * Utility for use as an [[ActorRef]] when *synchronously* testing [[akka.actor.typed.Behavior]]
 * with [[akka.actor.testkit.typed.javadsl.BehaviorTestKit]].
 *
 * If you plan to use a real [[akka.actor.typed.ActorSystem]] then use [[akka.actor.testkit.typed.javadsl.TestProbe]]
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
