/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed.javadsl

import scala.annotation.tailrec
import akka.japi.function.{ Function, Function2, Predicate }
import akka.typed
import akka.typed.{ Behavior, ExtensibleBehavior, Signal }
import BehaviorChain._
import akka.annotation.InternalApi

/**
 * Used for creating a [[Behavior]] by 'chaining' message and signal handlers.
 *
 * When handling a message or signal, this [[Behavior]] will consider all handlers in the order they were added,
 * looking for the first handler for which both the type and the (optional) predicate match.
 *
 * @tparam T the common superclass of all supported messages.
 */
class BehaviorChain[T] private (
  private val messageHandlers: List[Case[T, T]],
  private val signalHandlers:  List[Case[T, Signal]]
) extends ExtensibleBehavior[T] {

  override def receiveMessage(ctx: typed.ActorContext[T], msg: T): Behavior[T] = receive[T](ctx, msg, messageHandlers)

  override def receiveSignal(ctx: typed.ActorContext[T], msg: Signal): Behavior[T] = receive[Signal](ctx, msg, signalHandlers)

  @tailrec
  private def receive[M](ctx: ActorContext[T], msg: M, handlers: List[Case[T, M]]): Behavior[T] =
    handlers match {
      case Case(cls, predicate, handler) :: tail ⇒
        if (cls.isAssignableFrom(msg.getClass) && (predicate.isEmpty || predicate.get.apply(msg))) handler(ctx, msg)
        else receive[M](ctx, msg, tail)
      case _ ⇒
        // emulate scala match error
        throw new MatchError(msg)
    }

  /**
   * Add a new case to the message handling.
   *
   * @param type type of message to match
   * @param handler action to apply if the type matches
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[M <: T](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers :+ Case[T, T](`type`, None, (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])), signalHandlers)

  /**
   * Add a new predicated case to the message handling.
   *
   * @param type type of message to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[M <: T](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers :+ Case[T, T](`type`, Some((t: T) ⇒ test.test(t.asInstanceOf[M])), (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])), signalHandlers)

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
  def messageUnchecked[M <: T](`type`: Class[_ <: T], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers :+ Case[T, T](`type`, None, (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])), signalHandlers)

  /**
   * Add a new case to the message handling matching equal messages.
   *
   * @param msg the message to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def messageEquals(msg: T, handler: Function[ActorContext[T], Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers :+ Case[T, T](msg.getClass, Some(_.equals(msg)), (ctx: ActorContext[T], _: T) ⇒ handler.apply(ctx)), signalHandlers)

  /**
   * Add a new case to the signal handling.
   *
   * @param type type of signal to match
   * @param handler action to apply if the type matches
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[M <: Signal](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers, signalHandlers :+ Case[T, Signal](`type`, None, (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])))

  /**
   * Add a new predicated case to the signal handling.
   *
   * @param type type of signals to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[M <: Signal](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers, signalHandlers :+ Case[T, Signal](`type`, Some((t: Signal) ⇒ test.test(t.asInstanceOf[M])), (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])))

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
  def signalUnchecked[M <: Signal](`type`: Class[_ <: Signal], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers, signalHandlers :+ Case[T, Signal](`type`, None, (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])))

  /**
   * Add a new case to the signal handling matching equal signals.
   *
   * @param signal the signal to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def signalEquals(signal: Signal, handler: Function[ActorContext[T], Behavior[T]]): BehaviorChain[T] =
    new BehaviorChain[T](messageHandlers, signalHandlers :+ Case[T, Signal](signal.getClass, Some(_.equals(signal)), (ctx: ActorContext[T], _: Signal) ⇒ handler.apply(ctx)))

}

object BehaviorChain {
  def create[T]: BehaviorChain[T] = new BehaviorChain[T](Nil, Nil)

  import scala.language.existentials

  /** INTERNAL API */
  @InternalApi
  private[javadsl] final case class Case[BT, MT](`type`: Class[_ <: MT], predicate: Option[MT ⇒ Boolean], handler: (ActorContext[BT], MT) ⇒ Behavior[BT])

  /**
   * Start a new behavior chain starting with this case.
   *
   * @param type type of message to match
   * @param handler action to apply if the type matches
   * @tparam T type of behavior to create
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[T, M <: T](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].message(`type`, handler)

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
  def message[T, M <: T](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].message(`type`, test, handler)

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
  def messageUnchecked[T, M <: T](`type`: Class[_ <: T], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].messageUnchecked(`type`, handler)

  /**
   * Start a new behavior chain starting with a handler for equal messages.
   *
   * @param msg the message to compare to
   * @param handler action to apply when the message matches
   * @tparam T type of behavior to create
   * @return a new behavior with the specified handling appended
   */
  def messageEquals[T](msg: T, handler: Function[ActorContext[T], Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].messageEquals(msg, handler)

  /**
   * Start a new behavior chain starting with this signal case.
   *
   * @param type type of signal to match
   * @param handler action to apply if the type matches
   * @tparam T type of behavior to create
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[T, M <: Signal](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].signal(`type`, handler)

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
  def signal[T, M <: Signal](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].signal(`type`, test, handler)

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
  def signalUnchecked[T, M <: Signal](`type`: Class[_ <: Signal], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].signalUnchecked(`type`, handler)

  /**
   * Start a new behavior chain starting with a handler for this specific signal.
   *
   * @param signal the signal to compare to
   * @param handler action to apply when the message matches
   * @tparam T type of behavior to create
   * @return a new behavior with the specified handling appended
   */
  def signalEquals[T](signal: Signal, handler: Function[ActorContext[T], Behavior[T]]): BehaviorChain[T] =
    BehaviorChain.create[T].signalEquals(signal, handler)

}