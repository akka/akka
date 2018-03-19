/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.typed.{ Behavior, ExtensibleBehavior, Signal }

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
  @throws(classOf[Exception])
  override final def receive(ctx: akka.actor.typed.ActorContext[T], msg: T): Behavior[T] =
    onMessage(msg)

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

  @throws(classOf[Exception])
  override final def receiveSignal(ctx: akka.actor.typed.ActorContext[T], msg: Signal): Behavior[T] =
    onSignal.applyOrElse(msg, { case _ ⇒ Behavior.unhandled }: PartialFunction[Signal, Behavior[T]])

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
}
