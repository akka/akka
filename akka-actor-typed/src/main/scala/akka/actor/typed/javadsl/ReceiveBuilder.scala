/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import scala.annotation.tailrec
import akka.japi.function.{ Creator, Function, Predicate }
import akka.actor.typed.{ Behavior, Signal }
import akka.annotation.InternalApi

/**
 * Used when implementing [[AbstractBehavior]].
 *
 * When handling a message or signal, this [[Behavior]] will consider all handlers in the order they were added,
 * looking for the first handler for which both the type and the (optional) predicate match.
 *
 * @tparam T the common superclass of all supported messages.
 */
class ReceiveBuilder[T] private (
  private val messageHandlers: List[ReceiveBuilder.Case[T, T]],
  private val signalHandlers:  List[ReceiveBuilder.Case[T, Signal]]
) {

  import ReceiveBuilder.Case

  def build(): Receive[T] = new BuiltReceive(messageHandlers.reverse, signalHandlers.reverse)

  /**
   * Add a new case to the message handling.
   *
   * @param type type of message to match
   * @param handler action to apply if the type matches
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def onMessage[M <: T](`type`: Class[M], handler: Function[M, Behavior[T]]): ReceiveBuilder[T] =
    withMessage(`type`, None, msg ⇒ handler.apply(msg.asInstanceOf[M]))

  /**
   * Add a new predicated case to the message handling.
   *
   * @param type type of message to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def onMessage[M <: T](`type`: Class[M], test: Predicate[M], handler: Function[M, Behavior[T]]): ReceiveBuilder[T] =
    withMessage(
      `type`,
      Some((t: T) ⇒ test.test(t.asInstanceOf[M])),
      msg ⇒ handler.apply(msg.asInstanceOf[M])
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
  def onMessageUnchecked[M <: T](`type`: Class[_ <: T], handler: Function[M, Behavior[T]]): ReceiveBuilder[T] =
    withMessage(`type`, None, msg ⇒ handler.apply(msg.asInstanceOf[M]))

  /**
   * Add a new case to the message handling matching equal messages.
   *
   * @param msg the message to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def onMessageEquals(msg: T, handler: Creator[Behavior[T]]): ReceiveBuilder[T] =
    withMessage(msg.getClass, Some(_.equals(msg)), _ ⇒ handler.create())

  /**
   * Add a new case to the signal handling.
   *
   * @param type type of signal to match
   * @param handler action to apply if the type matches
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def onSignal[M <: Signal](`type`: Class[M], handler: Function[M, Behavior[T]]): ReceiveBuilder[T] =
    withSignal(`type`, None, signal ⇒ handler.apply(signal.asInstanceOf[M]))

  /**
   * Add a new predicated case to the signal handling.
   *
   * @param type type of signals to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def onSignal[M <: Signal](`type`: Class[M], test: Predicate[M], handler: Function[M, Behavior[T]]): ReceiveBuilder[T] =
    withSignal(
      `type`,
      Some((t: Signal) ⇒ test.test(t.asInstanceOf[M])),
      signal ⇒ handler.apply(signal.asInstanceOf[M])
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
  def onSignalUnchecked[M <: Signal](`type`: Class[_ <: Signal], handler: Function[M, Behavior[T]]): ReceiveBuilder[T] =
    withSignal(`type`, None, signal ⇒ handler.apply(signal.asInstanceOf[M]))

  /**
   * Add a new case to the signal handling matching equal signals.
   *
   * @param signal the signal to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def onSignalEquals(signal: Signal, handler: Creator[Behavior[T]]): ReceiveBuilder[T] =
    withSignal(signal.getClass, Some(_.equals(signal)), _ ⇒ handler.create())

  private def withMessage(`type`: Class[_ <: T], test: Option[T ⇒ Boolean], handler: T ⇒ Behavior[T]): ReceiveBuilder[T] =
    new ReceiveBuilder[T](Case[T, T](`type`, test, handler) +: messageHandlers, signalHandlers)

  private def withSignal[M <: Signal](`type`: Class[M], test: Option[Signal ⇒ Boolean], handler: Signal ⇒ Behavior[T]): ReceiveBuilder[T] =
    new ReceiveBuilder[T](messageHandlers, Case[T, Signal](`type`, test, handler) +: signalHandlers)
}

object ReceiveBuilder {
  def create[T]: ReceiveBuilder[T] = new ReceiveBuilder[T](Nil, Nil)

  /** INTERNAL API */
  @InternalApi
  private[javadsl] final case class Case[BT, MT](`type`: Class[_ <: MT], test: Option[MT ⇒ Boolean], handler: MT ⇒ Behavior[BT])

}

/**
 * Receive type for [[AbstractBehavior]]
 */
private final class BuiltReceive[T](
  private val messageHandlers: List[ReceiveBuilder.Case[T, T]],
  private val signalHandlers:  List[ReceiveBuilder.Case[T, Signal]]
) extends Receive[T] {
  import ReceiveBuilder.Case

  override def receiveMessage(msg: T): Behavior[T] = receive[T](msg, messageHandlers)

  override def receiveSignal(msg: Signal): Behavior[T] = receive[Signal](msg, signalHandlers)

  @tailrec
  private def receive[M](msg: M, handlers: List[Case[T, M]]): Behavior[T] =
    handlers match {
      case Case(cls, predicate, handler) :: tail ⇒
        if (cls.isAssignableFrom(msg.getClass) && (predicate.isEmpty || predicate.get.apply(msg))) handler(msg)
        else receive[M](msg, tail)
      case _ ⇒
        Behaviors.unhandled
    }

}
