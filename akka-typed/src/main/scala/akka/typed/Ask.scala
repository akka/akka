/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.Future
import akka.util.Timeout
import akka.actor.InternalActorRef
import akka.pattern.AskTimeoutException
import akka.pattern.PromiseActorRef
import java.lang.IllegalArgumentException

/**
 * The ask-pattern implements the initiator side of a request–reply protocol.
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
    def ?[U](f: ActorRef[U] ⇒ T)(implicit timeout: Timeout): Future[U] = ask(ref, timeout, f)
  }

  private class PromiseRef[U](actorRef: ActorRef[_], timeout: Timeout) {
    val (ref: ActorRef[U], future: Future[U], promiseRef: PromiseActorRef) = actorRef.untypedRef match {
      case ref: InternalActorRef if ref.isTerminated ⇒
        (ActorRef[U](ref.provider.deadLetters),
          Future.failed[U](new AskTimeoutException(s"Recipient[$actorRef] had already been terminated.")))
      case ref: InternalActorRef ⇒
        if (timeout.duration.length <= 0)
          (ActorRef[U](ref.provider.deadLetters),
            Future.failed[U](new IllegalArgumentException(s"Timeout length must not be negative, question not sent to [$actorRef]")))
        else {
          val a = PromiseActorRef(ref.provider, timeout, actorRef, "unknown")
          val b = ActorRef[U](a)
          (b, a.result.future.asInstanceOf[Future[U]], a)
        }
      case _ ⇒ throw new IllegalArgumentException(s"cannot create PromiseRef for non-Akka ActorRef (${actorRef.getClass})")
    }
  }

  private object PromiseRef {
    def apply[U](actorRef: ActorRef[_])(implicit timeout: Timeout) = new PromiseRef[U](actorRef, timeout)
  }

  private[typed] def ask[T, U](actorRef: ActorRef[T], timeout: Timeout, f: ActorRef[U] ⇒ T): Future[U] = {
    val p = PromiseRef[U](actorRef)(timeout)
    val m = f(p.ref)
    p.promiseRef.messageClassName = m.getClass.getName
    actorRef ! m
    p.future
  }

}
