/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import akka.actor.typed.{ Behavior, ExtensibleBehavior, Signal, TypedActorContext }
import akka.util.OptionVal

/**
 * An actor `Behavior` can be implemented by extending this class and implement the
 * abstract method [[AbstractBehavior#createReceive]]. Mutable state can be defined
 * as instance variables of the class.
 *
 * This is an Object-oriented style of defining a `Behavior`. A more functional style
 * alternative is provided by the factory methods in [[Behaviors]], for example
 * [[Behaviors.receiveMessage]].
 *
 * Instances of this behavior should be created via [[Behaviors.setup]] and if
 * the [[ActorContext]] is needed it can be passed as a constructor parameter
 * from the factory function.
 *
 * @see [[Behaviors.setup]]
 */
abstract class AbstractBehavior[T] extends ExtensibleBehavior[T] {
  private var _receive: OptionVal[Receive[T]] = OptionVal.None
  private def receive: Receive[T] = _receive match {
    case OptionVal.None ⇒
      val receive = createReceive
      _receive = OptionVal.Some(receive)
      receive
    case OptionVal.Some(r) ⇒ r
  }

  @throws(classOf[Exception])
  override final def receive(ctx: TypedActorContext[T], msg: T): Behavior[T] =
    receive.receive(ctx, msg)

  @throws(classOf[Exception])
  override final def receiveSignal(ctx: TypedActorContext[T], msg: Signal): Behavior[T] =
    receive.receiveSignal(ctx, msg)

  /**
   * Implement this to define how messages and signals are processed. Use the
   * [[AbstractBehavior.receiveBuilder]] to define the message dispatch.
   */
  def createReceive: Receive[T]

  /**
   * Create a [[ReceiveBuilder]] to define the message dispatch of the `Behavior`.
   * Typically used from [[AbstractBehavior.createReceive]].
   */
  def receiveBuilder: ReceiveBuilder[T] = ReceiveBuilder.create
}
