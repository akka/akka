/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.internal

import java.util.concurrent.{ ConcurrentLinkedQueue, ThreadLocalRandom }

import akka.actor.typed.ActorRef
import akka.actor.{ Address, RootActorPath }
import akka.annotation.InternalApi

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class TestInboxImpl[T](name: String)
  extends akka.testkit.typed.javadsl.TestInbox[T]
  with akka.testkit.typed.scaladsl.TestInbox[T] {

  private val q = new ConcurrentLinkedQueue[T]

  override val ref: ActorRef[T] = {
    val uid = ThreadLocalRandom.current().nextInt()
    val path = RootActorPath(Address("akka.actor.typed.inbox", "anonymous")).child(name).withUid(uid)
    new FunctionRef[T](path, (msg, self) ⇒ q.add(msg), (self) ⇒ ())
  }
  override def getRef() = ref

  override def receiveMessage(): T = q.poll() match {
    case null ⇒ throw new NoSuchElementException(s"polling on an empty inbox: $name")
    case x    ⇒ x
  }

  override def expectMessage(expectedMessage: T): TestInboxImpl[T] = {
    q.poll() match {
      case null    ⇒ assert(assertion = false, s"expected msg: $expectedMessage but no messages were received")
      case message ⇒ assert(message == expectedMessage, s"expected: $expectedMessage but received $message")
    }
    this
  }

  override protected def internalReceiveAll(): immutable.Seq[T] = {
    @tailrec def rec(acc: List[T]): List[T] = q.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }

    rec(Nil)
  }

  def hasMessages: Boolean = q.peek() != null

}
