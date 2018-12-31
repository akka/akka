/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.typed.{ Behavior, ExtensibleBehavior, Signal, TypedActorContext }

/**
 * An actor `Behavior` can be implemented by extending this class and implement the
 * abstract method [[AbstractBehavior#onMessage]] and optionally override
 * [[AbstractBehavior#onSignal]]. Mutable state can be defined as instance variables
 * of the class.
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

  /**
   * Implement this method to process an incoming message and return the next behavior.
   *
   * The returned behavior can in addition to normal behaviors be one of the canned special objects:
   * <ul>
   * <li>returning `stopped` will terminate this Behavior</li>
   * <li>returning `this` or `same` designates to reuse the current Behavior</li>
   * <li>returning `unhandled` keeps the same Behavior and signals that the message was not yet handled</li>
   * </ul>
   *
   */
  @throws(classOf[Exception])
  def onMessage(msg: T): Behavior[T]

  /**
   * Override this method to process an incoming [[akka.actor.typed.Signal]] and return the next behavior.
   * This means that all lifecycle hooks, ReceiveTimeout, Terminated and Failed messages
   * can initiate a behavior change.
   *
   * The returned behavior can in addition to normal behaviors be one of the canned special objects:
   *
   *  * returning `stopped` will terminate this Behavior
   *  * returning `this` or `same` designates to reuse the current Behavior
   *  * returning `unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * By default, partial function is empty and does not handle any signals.
   */
  @throws(classOf[Exception])
  def onSignal: PartialFunction[Signal, Behavior[T]] = PartialFunction.empty

  @throws(classOf[Exception])
  override final def receive(ctx: TypedActorContext[T], msg: T): Behavior[T] =
    onMessage(msg)

  @throws(classOf[Exception])
  override final def receiveSignal(ctx: TypedActorContext[T], msg: Signal): Behavior[T] =
    onSignal.applyOrElse(msg, { case _ ⇒ Behavior.unhandled }: PartialFunction[Signal, Behavior[T]])
}
