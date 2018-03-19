/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import akka.actor.typed.{ Behavior, ExtensibleBehavior, Signal }
import akka.actor.typed.javadsl.Behaviors.Receive
import akka.util.OptionVal

/**
 * Mutable behavior can be implemented by extending this class and implement the
 * abstract method [[MutableBehavior#onMessage]] and optionally override
 * [[MutableBehavior#onSignal]].
 *
 * Instances of this behavior should be created via [[Behaviors#setup]] and if
 * the [[ActorContext]] is needed it can be passed as a constructor parameter
 * from the factory function.
 *
 * @see [[Behaviors#setup]]
 */
abstract class MutableBehavior[T] extends ExtensibleBehavior[T] {
  private var _receive: OptionVal[Receive[T]] = OptionVal.None
  private def receive: Receive[T] = _receive match {
    case OptionVal.None ⇒
      val receive = createReceive
      _receive = OptionVal.Some(receive)
      receive
    case OptionVal.Some(r) ⇒ r
  }

  @throws(classOf[Exception])
  override final def receive(ctx: akka.actor.typed.ActorContext[T], msg: T): Behavior[T] =
    receive.receive(ctx, msg)

  @throws(classOf[Exception])
  override final def receiveSignal(ctx: akka.actor.typed.ActorContext[T], msg: Signal): Behavior[T] =
    receive.receiveSignal(ctx, msg)

  def createReceive: Receive[T]

  def receiveBuilder: ReceiveBuilder[T] = ReceiveBuilder.create
}
