/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.internal

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.typed.ActorRef
import akka.actor.typed.internal.{ ActorRefImpl, SystemMessage }
import akka.annotation.InternalApi
import akka.{ actor ⇒ a }

import scala.annotation.tailrec

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class DebugRef[T](override val path: a.ActorPath, override val isLocal: Boolean)
  extends ActorRef[T] with ActorRefImpl[T] {

  private val q = new ConcurrentLinkedQueue[Either[SystemMessage, T]]

  override def tell(msg: T): Unit = q.add(Right(msg))
  override def sendSystem(signal: SystemMessage): Unit = q.add(Left(signal))

  def hasMessage: Boolean = q.peek match {
    case null     ⇒ false
    case Left(_)  ⇒ false
    case Right(_) ⇒ true
  }

  def hasSignal: Boolean = q.peek match {
    case null     ⇒ false
    case Left(_)  ⇒ true
    case Right(_) ⇒ false
  }

  def hasSomething: Boolean = q.peek != null

  def receiveMessage(): T = q.poll match {
    case null         ⇒ throw new NoSuchElementException("empty DebugRef")
    case Left(signal) ⇒ throw new IllegalStateException(s"expected message but found signal $signal")
    case Right(msg)   ⇒ msg
  }

  def receiveSignal(): SystemMessage = q.poll match {
    case null         ⇒ throw new NoSuchElementException("empty DebugRef")
    case Left(signal) ⇒ signal
    case Right(msg)   ⇒ throw new IllegalStateException(s"expected signal but found message $msg")
  }

  def receiveAll(): List[Either[SystemMessage, T]] = {
    @tailrec def rec(acc: List[Either[SystemMessage, T]]): List[Either[SystemMessage, T]] =
      q.poll match {
        case null  ⇒ acc.reverse
        case other ⇒ rec(other :: acc)
      }
    rec(Nil)
  }
}
