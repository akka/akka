/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import scala.concurrent.{ Future, Promise }
import akka.util.Timeout
import akka.actor.InternalActorRef
import akka.pattern.AskTimeoutException
import akka.pattern.PromiseActorRef
import akka.actor.Scheduler
import akka.actor.RootActorPath
import akka.actor.Address
import akka.annotation.InternalApi
import akka.actor.typed.ActorRef
import akka.actor.typed.internal.{ adapter ⇒ adapt }

/**
 * The ask-pattern implements the initiator side of a request–reply protocol.
 * The `?` operator is pronounced as "ask".
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
 * implicit val timeout = Timeout(3.seconds)
 * val target: ActorRef[Request] = ...
 * val f: Future[Reply] = target ? (Request("hello", _))
 * }}}
 */
object AskPattern {
  implicit class Askable[T](val ref: ActorRef[T]) extends AnyVal {
    /**
     * The ask-pattern implements the initiator side of a request–reply protocol.
     * The `?` operator is pronounced as "ask".
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
     * implicit val timeout = Timeout(3.seconds)
     * val target: ActorRef[Request] = ...
     * val f: Future[Reply] = target ? (Request("hello", _))
     * }}}
     */
    def ?[U](f: ActorRef[U] ⇒ T)(implicit timeout: Timeout): Future[U] =
      ref match {
        case a: adapt.ActorRefAdapter[_]    ⇒ askUntyped(ref, a.untyped, timeout, f)
        case a: adapt.ActorSystemAdapter[_] ⇒ askUntyped(ref, a.untyped.guardian, timeout, f)
        case a                              ⇒ throw new IllegalStateException("Only expect actor references to be ActorRefAdapter or ActorSystemAdapter until native system is implemented: " + a.getClass)
      }
  }

  private final class PromiseRef[U](target: ActorRef[_], untyped: InternalActorRef, timeout: Timeout) {

    // Note: _promiseRef mustn't have a type pattern, since it can be null
    private[this] val (_ref: ActorRef[U], _future: Future[U], _promiseRef) =
      if (untyped.isTerminated)
        (
          adapt.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new AskTimeoutException(s"Recipient[$target] had already been terminated.")), null)
      else if (timeout.duration.length <= 0)
        (
          adapt.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new IllegalArgumentException(s"Timeout length must be positive, question not sent to [$target]")), null)
      else {
        val a = PromiseActorRef(untyped.provider, timeout, target, "unknown")
        val b = adapt.ActorRefAdapter[U](a)
        (b, a.result.future.asInstanceOf[Future[U]], a)
      }

    val ref: ActorRef[U] = _ref
    val future: Future[U] = _future
    val promiseRef: PromiseActorRef = _promiseRef
  }

  private def askUntyped[T, U](target: ActorRef[T], untyped: InternalActorRef, timeout: Timeout, f: ActorRef[U] ⇒ T): Future[U] = {
    val p = new PromiseRef[U](target, untyped, timeout)
    val m = f(p.ref)
    if (p.promiseRef ne null) p.promiseRef.messageClassName = m.getClass.getName
    target ! m
    p.future
  }

  private[typed] val AskPath = RootActorPath(Address("akka.actor.typed.internal", "ask"))
}
