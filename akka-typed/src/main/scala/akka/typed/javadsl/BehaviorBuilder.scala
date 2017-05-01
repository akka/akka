/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed.javadsl

import scala.annotation.tailrec
import akka.japi.function.{ Function, Function2, Predicate }
import akka.typed
import akka.typed.{ Behavior, ExtensibleBehavior, Signal }
import BehaviorBuilder._
import akka.annotation.InternalApi

/**
 * Used for creating a [[akka.typed.Behavior]].
 *
 * There are methods for matching both messages and signals on type and (optionally) predicate.
 *
 * @tparam T the common superclass of all supported messages.
 */
case class BehaviorBuilder[T](
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
   * @param `type` type of message to match
   * @param handler action to apply if the type matches
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[M <: T](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](messageHandlers = messageHandlers :+ Case[T, T](`type`, None, (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])))

  /**
   * Add a new predicated case to the message handling.
   *
   * @param `type` type of message to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of message to match
   * @return a new behavior with the specified handling appended
   */
  def message[M <: T](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](messageHandlers = messageHandlers :+ Case[T, T](`type`, Some((t: T) ⇒ test.test(t.asInstanceOf[M])), (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])))

  /**
   * Add a new case to the message handling without compile time type check.
   * Should normally not be used, but when matching on class with generic type
   * argument it can be useful, e.g. <code>List.class</code> and <code>(List&lt;String&gt; list) -> {...}</code>
   *
   * @param `type` type of message to match
   * @param handler action to apply when the type matches
   * @return a new behavior with the specified handling appended
   */
  def messageUnchecked[M <: T](`type`: Class[_ <: T], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](messageHandlers = messageHandlers :+ Case[T, T](`type`, None, (i1: ActorContext[T], msg: T) ⇒ handler.apply(i1, msg.asInstanceOf[M])))

  /**
   * Add a new case to the message handling matching equal messages.
   *
   * @param msg the message to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def messageEquals(msg: T, handler: Function[ActorContext[T], Behavior[T]]): BehaviorBuilder[T] =
    copy[T](messageHandlers = messageHandlers :+ Case[T, T](msg.getClass, Some(_.equals(msg)), (ctx: ActorContext[T], _: T) ⇒ handler.apply(ctx)))

  /**
   * Add a new case to the signal handling
   *
   * @param `type` type of signal to match
   * @param handler action to apply if the type matches
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[M <: Signal](`type`: Class[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](signalHandlers = signalHandlers :+ Case[T, Signal](`type`, None, (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])))

  /**
   * Add a new predicated case to the signal handling.
   *
   * @param `type` type of signals to match
   * @param test a predicate that will be evaluated on the argument if the type matches
   * @param handler action to apply if the type matches and the predicate returns true
   * @tparam M type of signal to match
   * @return a new behavior with the specified handling appended
   */
  def signal[M <: Signal](`type`: Class[M], test: Predicate[M], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](signalHandlers = signalHandlers :+ Case[T, Signal](`type`, Some((t: Signal) ⇒ test.test(t.asInstanceOf[M])), (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])))

  /**
   * Add a new case to the signal handling without compile time type check.
   * Should normally not be used, but when matching on class with generic type
   * argument it can be useful, e.g. <code>GenMsg.class</code> and <code>(ActorContext<Message> ctx, GenMsg&lt;String&gt; list) -> {...}</code>
   *
   * @param `type` type of signal to match
   * @param handler action to apply when the type matches
   * @return a new behavior with the specified handling appended
   */
  def signalUnchecked[M <: Signal](`type`: Class[_ <: Signal], handler: Function2[ActorContext[T], M, Behavior[T]]): BehaviorBuilder[T] =
    copy[T](signalHandlers = signalHandlers :+ Case[T, Signal](`type`, None, (ctx: ActorContext[T], signal: Signal) ⇒ handler.apply(ctx, signal.asInstanceOf[M])))

  /**
   * Add a new case to the signal handling matching equal signals.
   *
   * @param signal the signal to compare to
   * @param handler action to apply when the message matches
   * @return a new behavior with the specified handling appended
   */
  def signalEquals(signal: Signal, handler: Function[ActorContext[T], Behavior[T]]): BehaviorBuilder[T] =
    copy[T](signalHandlers = signalHandlers :+ Case[T, Signal](signal.getClass, Some(_.equals(signal)), (ctx: ActorContext[T], _: Signal) ⇒ handler.apply(ctx)))

}

object BehaviorBuilder {
  def create[T](): BehaviorBuilder[T] = BehaviorBuilder[T](Nil, Nil)

  import scala.language.existentials

  @InternalApi
  private[javadsl] final case class Case[BT, MT](`type`: Class[_ <: MT], predicate: Option[MT ⇒ Boolean], handler: (ActorContext[BT], MT) ⇒ Behavior[BT])

}