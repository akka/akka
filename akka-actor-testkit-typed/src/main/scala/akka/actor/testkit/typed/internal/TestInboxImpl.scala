/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.tailrec
import scala.collection.immutable

import akka.actor.ActorPath
import akka.actor.typed.ActorRef
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class TestInboxImpl[T](path: ActorPath)
    extends akka.actor.testkit.typed.javadsl.TestInbox[T]
    with akka.actor.testkit.typed.scaladsl.TestInbox[T] {

  private val q = new ConcurrentLinkedQueue[T]

  override val ref: ActorRef[T] = new FunctionRef[T](path, (message, _) => q.add(message))
  override def getRef() = ref

  override def receiveMessage(): T = q.poll() match {
    case null => throw new NoSuchElementException(s"polling on an empty inbox: $path")
    case x    => x
  }

  override def expectMessage(expectedMessage: T): TestInboxImpl[T] = {
    q.poll() match {
      case null    => assert(assertion = false, s"expected message: $expectedMessage but no messages were received")
      case message => assert(message == expectedMessage, s"expected: $expectedMessage but received $message")
    }
    this
  }

  override protected def internalReceiveAll(): immutable.Seq[T] = {
    @tailrec def rec(acc: List[T]): List[T] = q.poll() match {
      case null => acc.reverse
      case x    => rec(x :: acc)
    }

    rec(Nil)
  }

  def hasMessages: Boolean = q.peek() != null

  @InternalApi private[akka] def as[U]: TestInboxImpl[U] = this.asInstanceOf[TestInboxImpl[U]]

}
