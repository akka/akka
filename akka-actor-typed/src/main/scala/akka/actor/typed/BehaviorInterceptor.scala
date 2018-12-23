/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.annotation.{ DoNotInherit, InternalApi }

/**
 * A behavior interceptor allows for intercepting message and signal reception and perform arbitrary logic -
 * transform, filter, send to a side channel etc. It is the core API for decoration of behaviors. Many built-in
 * intercepting behaviors are provided through factories in the respective `Behaviors`.
 *
 * @tparam O The outer message type – the type of messages the intercepting behavior will accept
 * @tparam I The inner message type - the type of message the wrapped behavior accepts
 */
abstract class BehaviorInterceptor[O, I] {
  import BehaviorInterceptor._

  /**
   * Override to intercept actor startup. To trigger startup of
   * the next behavior in the stack, call `target.start()`.
   * @return The returned behavior will be the "started" behavior of the actor used to accept
   *         the next message or signal.
   */
  def aroundStart(ctx: TypedActorContext[O], target: PreStartTarget[I]): Behavior[I] =
    target.start(ctx)

  /**
   * Intercept a message sent to the running actor. Pass the message on to the next behavior
   * in the stack by passing it to `target.apply`, return `Behaviors.same` without invoking `target`
   * to filter out the message.
   *
   * @return The behavior for next message or signal
   */
  def aroundReceive(ctx: TypedActorContext[O], msg: O, target: ReceiveTarget[I]): Behavior[I]

  /**
   * Intercept a signal sent to the running actor. Pass the signal on to the next behavior
   * in the stack by passing it to `target.apply`.
   *
   * @return The behavior for next message or signal
   */
  def aroundSignal(ctx: TypedActorContext[O], signal: Signal, target: SignalTarget[I]): Behavior[I]

  /**
   * @return `true` if this behavior logically the same as another behavior interceptor and can therefore be eliminated
   *         (to avoid building infinitely growing stacks of behaviors)? Default implementation is based on instance
   *         equality. Override to provide use case specific logic.
   */
  def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = this eq other

}

object BehaviorInterceptor {

  /**
   * Abstraction of passing the on further in the behavior stack in [[BehaviorInterceptor#preStart]].
   *
   * Not for user extension
   */
  @DoNotInherit
  trait PreStartTarget[T] {
    def start(ctx: TypedActorContext[_]): Behavior[T]
  }

  /**
   * Abstraction of passing the message on further in the behavior stack in [[BehaviorInterceptor#aroundReceive]].
   *
   * Not for user extension
   */
  @DoNotInherit
  trait ReceiveTarget[T] {
    def apply(ctx: TypedActorContext[_], msg: T): Behavior[T]

    /**
     * INTERNAL API
     *
     * Signal that the received message will result in a simulated restart
     * by the [[BehaviorInterceptor]]. A [[PreRestart]] will be sent to the
     * current behavior but the returned Behavior is ignored as a restart
     * is taking place.
     */
    @InternalApi
    private[akka] def signalRestart(ctx: TypedActorContext[_]): Unit
  }

  /**
   * Abstraction of passing the signal on further in the behavior stack in [[BehaviorInterceptor#aroundReceive]].
   *
   * Not for user extension
   */
  @DoNotInherit
  trait SignalTarget[T] {
    def apply(ctx: TypedActorContext[_], signal: Signal): Behavior[T]
  }

}
