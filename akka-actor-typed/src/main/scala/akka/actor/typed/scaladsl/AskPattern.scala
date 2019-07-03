/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.TimeoutException

import scala.concurrent.Future
import akka.actor.{ Address, RootActorPath }
import akka.actor.typed.ActorRef
import akka.actor.typed.Scheduler
import akka.actor.typed.internal.{ adapter => adapt }
import akka.annotation.InternalApi
import akka.pattern.PromiseActorRef
import akka.util.Timeout
import akka.actor.typed.RecipientRef
import akka.actor.typed.internal.InternalRecipientRef
import com.github.ghik.silencer.silent

/**
 * The ask-pattern implements the initiator side of a request–reply protocol.
 *
 * See [[AskPattern.Askable.ask]] for details
 */
object AskPattern {

  /**
   * See [[ask]]
   *
   * @tparam Req The request protocol, what the other actor accepts
   */
  implicit final class Askable[Req](val ref: RecipientRef[Req]) extends AnyVal {

    /**
     * The ask-pattern implements the initiator side of a request–reply protocol.
     * The `?` operator is pronounced as "ask" (and a convenience symbolic operation
     * kept since the previous ask API, if unsure which one to use, prefer the non-symbolic
     * method as it leads to fewer surprises with the scope of the `replyTo` function)
     *
     * Note that if you are inside of an actor you should prefer [[ActorContext.ask]]
     * as that provides better safety.
     *
     * The party that asks may be within or without an Actor, since the
     * implementation will fabricate a (hidden) [[ActorRef]] that is bound to a
     * [[scala.concurrent.Promise]]. This ActorRef will need to be injected in the
     * message that is sent to the target Actor in order to function as a reply-to
     * address, therefore the argument to the ask / `?`
     * operator is not the message itself but a function that given the reply-to
     * address will create the message.
     *
     * {{{
     * case class Request(msg: String, replyTo: ActorRef[Reply])
     * case class Reply(msg: String)
     *
     * implicit val scheduler = system.scheduler
     * implicit val timeout = Timeout(3.seconds)
     * val target: ActorRef[Request] = ...
     * val f: Future[Reply] = target ? (replyTo => Request("hello", replyTo))
     * }}}
     *
     * Note: it is preferrable to use the non-symbolic ask method as it easier allows for wildcards for
     * the `replyTo: ActorRef`.
     *
     * @tparam Res The response protocol, what the other actor sends back
     */
    def ?[Res](replyTo: ActorRef[Res] => Req)(implicit timeout: Timeout, scheduler: Scheduler): Future[Res] = {
      ask(replyTo)(timeout, scheduler)
    }

    /**
     * The ask-pattern implements the initiator side of a request–reply protocol.
     *
     * Note that if you are inside of an actor you should prefer [[ActorContext.ask]]
     * as that provides better safety.
     *
     * The party that asks may be within or without an Actor, since the
     * implementation will fabricate a (hidden) [[ActorRef]] that is bound to a
     * [[scala.concurrent.Promise]]. This ActorRef will need to be injected in the
     * message that is sent to the target Actor in order to function as a reply-to
     * address, therefore the argument to the ask / `?`
     * operator is not the message itself but a function that given the reply-to
     * address will create the message.
     *
     * {{{
     * case class Request(msg: String, replyTo: ActorRef[Reply])
     * case class Reply(msg: String)
     *
     * implicit val scheduler = system.scheduler
     * implicit val timeout = Timeout(3.seconds)
     * val target: ActorRef[Request] = ...
     * val f: Future[Reply] = target.ask(replyTo => Request("hello", replyTo))
     * // alternatively
     * val f2: Future[Reply] = target.ask(Request("hello", _))
     * }}}
     *
     * @tparam Res The response protocol, what the other actor sends back
     */
    @silent
    def ask[Res](replyTo: ActorRef[Res] => Req)(implicit timeout: Timeout, scheduler: Scheduler): Future[Res] = {
      // We do not currently use the implicit sched, but want to require it
      // because it might be needed when we move to a 'native' typed runtime, see #24219
      ref match {
        case a: InternalRecipientRef[_] => askUntyped(a, timeout, replyTo)
        case a =>
          throw new IllegalStateException(
            "Only expect references to be RecipientRef, ActorRefAdapter or ActorSystemAdapter until " +
            "native system is implemented: " + a.getClass)
      }
    }
  }

  private val onTimeout: String => Throwable = msg => new TimeoutException(msg)

  private final class PromiseRef[U](target: InternalRecipientRef[_], timeout: Timeout) {

    // Note: _promiseRef mustn't have a type pattern, since it can be null
    private[this] val (_ref: ActorRef[U], _future: Future[U], _promiseRef) =
      if (target.isTerminated)
        (
          adapt.ActorRefAdapter[U](target.provider.deadLetters),
          Future.failed[U](new TimeoutException(s"Recipient[$target] had already been terminated.")),
          null)
      else if (timeout.duration.length <= 0)
        (
          adapt.ActorRefAdapter[U](target.provider.deadLetters),
          Future.failed[U](
            new IllegalArgumentException(s"Timeout length must be positive, question not sent to [$target]")),
          null)
      else {
        // messageClassName "unknown' is set later, after applying the message factory
        val a = PromiseActorRef(target.provider, timeout, target, "unknown", onTimeout = onTimeout)
        val b = adapt.ActorRefAdapter[U](a)
        (b, a.result.future.asInstanceOf[Future[U]], a)
      }

    val ref: ActorRef[U] = _ref
    val future: Future[U] = _future
    val promiseRef: PromiseActorRef = _promiseRef
  }

  private def askUntyped[T, U](target: InternalRecipientRef[T], timeout: Timeout, f: ActorRef[U] => T): Future[U] = {
    val p = new PromiseRef[U](target, timeout)
    val m = f(p.ref)
    if (p.promiseRef ne null) p.promiseRef.messageClassName = m.getClass.getName
    target ! m
    p.future
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[typed] val AskPath = RootActorPath(Address("akka.actor.typed.internal", "ask"))
}
