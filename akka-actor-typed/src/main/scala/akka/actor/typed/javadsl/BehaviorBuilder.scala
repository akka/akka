/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import scala.annotation.tailrec

import akka.japi.function.{ Function, Function2, Predicate }
import akka.annotation.InternalApi
import akka.actor.typed
import akka.actor.typed.{ Behavior, ExtensibleBehavior, Signal }

import akka.actor.typed.Behavior.unhandled

import BehaviorBuilder._

/**
 * Used for creating a [[Behavior]] by 'chaining' message and signal handlers.
 *
 * When handling a message or signal, this [[Behavior]] will consider all handlers in the order they were added,
 * looking for the first handler for which both the type and the (optional) predicate match.
 *
 * @tparam T the common superclass of all supported messages.
 */
class BehaviorBuilder[T] private (
  private val messageHandlers: List[Case[T, T]],
  private val signalHandlers:  List[Case[T, Signal]]
) {

  def build(): Behavior[T] = new BuiltBehavior(messageHandlers.reverse, signalHandlers.reverse)

  /**
   * Add a new case to the message handling.
   *
   * @param type type of message to match
   * @param handler action to apply if the type matches
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def onMessage[M <: T](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    withMessage(`type`, None, (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M]))

  /**
   * Add a new predicated case to the message handling.
   *
   * @param type type of message to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def onMessage[M <: T](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    withMessage(
      `type`,
      Some((t: T) ⇒ test.test(t.asInstanceOf[M])),
      (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])
    )

  /**
   * Add a new case to the message handling without compile time type check.
   *
   * Should normally not be used, but when matching on class with generic type
   * argument it can be useful, e.g. <code>List.class</code> and <code>(List&lt;String&gt; list) -> {...}</code>
   *
   * @param type type of message to match
   * @param handler action to apply when the type matches
   * @return a new behavior with the specified handling appended
   */
  def onMessageUnchecked[M <: T](`type`: Class[_ <: T], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    withMessage(`type`, None, (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M]))

  /**
   * Add a new case to the message handling matching equal messages.
   *
   * @param msg the message to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def onMessageEquals(msg: T, handler: Function[ActorContext[T], Behavior[T]]): BehaviorBuilder[T] =
    withMessage(msg.getClass, Some(_.equals(msg)), (ctx: ActorContext[T], _: T) ⇒ handler.apply(ctx))

  /**
   * Add a new case to the signal handling.
   *
   * @param type type of signal to match
   * @param handler action to apply if the type matches
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def onSignal[M <: Signal](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    withSignal(`type`, None, (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M]))

  /**
   * Add a new predicated case to the signal handling.
   *
   * @param type type of signals to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def onSignal[M <: Signal](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    withSignal(
      `type`,
      Some((t: Signal) ⇒ test.test(t.asInstanceOf[M])),
      (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])
    )

  /**
   * Add a new case to the signal handling without compile time type check.
   *
   * Should normally not be used, but when matching on class with generic type
   * argument it can be useful, e.g. <code>GenMsg.class</code> and <code>(ActorContext<Message> ctx, GenMsg&lt;String&gt; list) -> {...}</code>
   *
   * @param type type of signal to match
   * @param handler action to apply when the type matches
   * @return a new behavior with the specified handling appended
   */
  def onSignalUnchecked[M <: Signal](`type`: Class[_ <: Signal], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    withSignal(`type`, None, (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M]))

  /**
   * Add a new case to the signal handling matching equal signals.
   *
   * @param signal the signal to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def onSignalEquals(signal: Signal, handler: Function[ActorContext[T], Behavior[T]]): BehaviorBuilder[T] =
    withSignal(signal.getClass, Some(_.equals(signal)), (ctx: ActorContext[T], _: Signal) ⇒ handler.apply(ctx))

  private def withMessage(`type`: Class[_ <: T], test: Option[T ⇒ Boolean], handler: (ActorContext[T], T) ⇒ Behavior[T]): BehaviorBuilder[T] =
    new BehaviorBuilder[T](Case[T, T](`type`, test, handler) +: messageHandlers, signalHandlers)

  private def withSignal[M <: Signal](`type`: Class[M], test: Option[Signal ⇒ Boolean], handler: (ActorContext[T], Signal) ⇒ Behavior[T]): BehaviorBuilder[T] =
    new BehaviorBuilder[T](messageHandlers, Case[T, Signal](`type`, test, handler) +: signalHandlers)
}

object BehaviorBuilder {
  def create[T]: BehaviorBuilder[T] = new BehaviorBuilder[T](Nil, Nil)

  /** INTERNAL API */
  @InternalApi
  private[javadsl] final case class Case[BT, MT](`type`: Class[_ <: MT], test: Option[MT ⇒ Boolean], handler: (ActorContext[BT], MT) ⇒ Behavior[BT])

  /**
   * Start a new behavior chain starting with this case.
   *
   * @param type type of message to match
   * @param handler action to apply if the type matches
   * @tparam T type of behavior to create
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[T, M <: T](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onMessage(`type`, handler)

  /**
   * Start a new behavior chain starting with this predicated case.
   *
   * @param type type of message to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam T type of behavior to create
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[T, M <: T](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onMessage(`type`, test, handler)

  /**
   * Start a new behavior chain starting with a handler without compile time type check.
   *
   * Should normally not be used, but when matching on class with generic type
   * argument it can be useful, e.g. <code>List.class</code> and <code>(List&lt;String&gt; list) -> {...}</code>
   *
   * @param type type of message to match
   * @param handler action to apply when the type matches
   * @return a new behavior with the specified handling appended
   */
  def messageUnchecked[T, M <: T](`type`: Class[_ <: T], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onMessageUnchecked(`type`, handler)

  /**
   * Start a new behavior chain starting with a handler for equal messages.
   *
   * @param msg the message to compare to
   * @param handler action to apply when the message matches
   * @tparam T type of behavior to create
   * @return a new behavior with the specified handling appended
   */
  def messageEquals[T](msg: T, handler: Function[ActorContext[T], Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onMessageEquals(msg, handler)

  /**
   * Start a new behavior chain starting with this signal case.
   *
   * @param type type of signal to match
   * @param handler action to apply if the type matches
   * @tparam T type of behavior to create
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[T, M <: Signal](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onSignal(`type`, handler)

  /**
   * Start a new behavior chain starting with this predicated signal case.
   *
   * @param type type of signals to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam T type of behavior to create
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[T, M <: Signal](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onSignal(`type`, test, handler)

  /**
   * Start a new behavior chain starting with this unchecked signal case.
   *
   * Should normally not be used, but when matching on class with generic type
   * argument it can be useful, e.g. <code>GenMsg.class</code> and <code>(ActorContext<Message> ctx, GenMsg&lt;String&gt; list) -> {...}</code>
   *
   * @param type type of signal to match
   * @param handler action to apply when the type matches
   * @return a new behavior with the specified handling appended
   */
  def signalUnchecked[T, M <: Signal](`type`: Class[_ <: Signal], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onSignalUnchecked(`type`, handler)

  /**
   * Start a new behavior chain starting with a handler for this specific signal.
   *
   * @param signal the signal to compare to
   * @param handler action to apply when the message matches
   * @tparam T type of behavior to create
   * @return a new behavior with the specified handling appended
   */
  def signalEquals[T](signal: Signal, handler: Function[ActorContext[T], Behavior[T]]): BehaviorBuilder[T] =
    BehaviorBuilder.create[T].onSignalEquals(signal, handler)

}

private class BuiltBehavior[T](
  private val messageHandlers: List[Case[T, T]],
  private val signalHandlers:  List[Case[T, Signal]]
) extends ExtensibleBehavior[T] {

  override def receive(ctx: typed.TypedActorContext[T], msg: T): Behavior[T] = receive[T](ctx.asJava, msg, messageHandlers)

  override def receiveSignal(ctx: typed.TypedActorContext[T], msg: Signal): Behavior[T] = receive[Signal](ctx.asJava, msg, signalHandlers)

  @tailrec
  private def receive[M](ctx: ActorContext[T], msg: M, handlers: List[Case[T, M]]): Behavior[T] =
    handlers match {
      case Case(cls, predicate, handler) :: tail ⇒
        if (cls.isAssignableFrom(msg.getClass) && (predicate.isEmpty || predicate.get.apply(msg))) handler(ctx, msg)
        else receive[M](ctx, msg, tail)
      case _ ⇒
        unhandled[T]
    }

}
