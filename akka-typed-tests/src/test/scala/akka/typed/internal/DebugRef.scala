/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.{ actor ⇒ a }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec

private[typed] class DebugRef[T](_path: a.ActorPath, override val isLocal: Boolean)
  extends ActorRef[T](_path) with ActorRefImpl[T] {

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
