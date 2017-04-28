/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed.javadsl

import scala.annotation.tailrec

import akka.japi.pf.FI.Apply2

import akka.typed
import akka.typed.{ Behavior, ExtensibleBehavior, Signal }

case class BehaviorBuilder[T](
  messageHandlers: List[(Class[_ <: T], (ActorContext[T], T) ⇒ Behavior[T])],
  signalHandlers:  List[(Class[_ <: Signal], (ActorContext[T], Signal) ⇒ Behavior[T])]
) {

  def build(): Behavior[T] = {
    new ExtensibleBehavior[T] {
      override def receiveMessage(ctx: typed.ActorContext[T], msg: T): Behavior[T] = receive[T](ctx, msg, messageHandlers)

      override def receiveSignal(ctx: typed.ActorContext[T], msg: Signal): Behavior[T] = receive[Signal](ctx, msg, signalHandlers)

      @tailrec
      private def receive[M](ctx: ActorContext[T], msg: M, handlers: List[(Class[_ <: M], (ActorContext[T], M) ⇒ Behavior[T])]): Behavior[T] =
        handlers match {
          case Nil ⇒
            // emulate scala match error
            throw new MatchError(msg)
          case (cls, impl) :: tail ⇒
            if (cls.isAssignableFrom(msg.getClass)) impl(ctx, msg)
            else receive[M](ctx, msg, tail)
        }
    }
  }

  def message[M <: T](`type`: Class[M], handler: Apply2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](messageHandlers = messageHandlers :+ ((`type`, (i1: ActorContext[T], i2: T) ⇒ handler.apply(i1, i2.asInstanceOf[M]))))

  def messageUnchecked(`type`: Class[_ <: T], handler: Apply2[ActorContext[T], T, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](messageHandlers = messageHandlers :+ ((`type`, (i1: ActorContext[T], i2: T) ⇒ handler.apply(i1, i2))))

  def signal[U <: Signal](`type`: Class[U], handler: Apply2[ActorContext[T], U, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](signalHandlers = signalHandlers :+ ((`type`, (i1: ActorContext[T], i2: Any) ⇒ handler.apply(i1, i2.asInstanceOf[U]))))

}

object BehaviorBuilder {
  def create[T](): BehaviorBuilder[T] = BehaviorBuilder[T](Nil, Nil)
}