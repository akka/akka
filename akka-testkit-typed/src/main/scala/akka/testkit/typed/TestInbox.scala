/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed

import java.util.concurrent.{ ConcurrentLinkedQueue, ThreadLocalRandom }

import akka.actor.{ Address, RootActorPath }
import akka.actor.typed.ActorRef
import akka.annotation.ApiMayChange

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * Utility for use as an [[ActorRef]] when synchronously testing [[akka.actor.typed.Behavior]]
 * to be used along with [[BehaviorTestkit]].
 *
 * See [[akka.testkit.typed.scaladsl.TestProbe]] for asynchronous testing.
 */
@ApiMayChange
class TestInbox[T](name: String) {
  def this() = this("inbox")

  private val q = new ConcurrentLinkedQueue[T]

  val ref: ActorRef[T] = {
    val uid = ThreadLocalRandom.current().nextInt()
    val path = RootActorPath(Address("akka.actor.typed.inbox", "anonymous")).child(name).withUid(uid)
    new FunctionRef[T](path, (msg, self) ⇒ q.add(msg), (self) ⇒ ())
  }

  /**
   * Get and remove the oldest message
   */
  def receiveMsg(): T = q.poll() match {
    case null ⇒ throw new NoSuchElementException(s"polling on an empty inbox: $name")
    case x    ⇒ x
  }

  /**
   * Assert and remove the the oldest message.
   */
  def expectMsg(expectedMessage: T): TestInbox[T] = {
    q.poll() match {
      case null    ⇒ assert(assertion = false, s"expected msg: $expectedMessage but no messages were received")
      case message ⇒ assert(message == expectedMessage, s"expected: $expectedMessage but received $message")
    }
    this
  }

  def receiveAll(): immutable.Seq[T] = {
    @tailrec def rec(acc: List[T]): List[T] = q.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }

    rec(Nil)
  }

  def hasMessages: Boolean = q.peek() != null

  // TODO expectNoMsg etc
}

@ApiMayChange
object TestInbox {
  def apply[T](name: String = "inbox"): TestInbox[T] = new TestInbox(name)
}
