/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.{ Future, Promise }
import akka.util.Timeout
import akka.actor.InternalActorRef
import akka.pattern.AskTimeoutException
import akka.pattern.PromiseActorRef
import java.lang.IllegalArgumentException
import akka.actor.Scheduler
import akka.typed.internal.FunctionRef
import akka.actor.RootActorPath
import akka.actor.Address
import akka.util.LineNumbers

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
    def ?[U](f: ActorRef[U] ⇒ T)(implicit timeout: Timeout, scheduler: Scheduler): Future[U] =
      ref match {
        case a: adapter.ActorRefAdapter[_]    ⇒ askUntyped(ref, a.untyped, timeout, f)
        case a: adapter.ActorSystemAdapter[_] ⇒ askUntyped(ref, a.untyped.guardian, timeout, f)
        case _                                ⇒ ask(ref, timeout, scheduler, f)
      }
  }

  private class PromiseRef[U](target: ActorRef[_], untyped: InternalActorRef, timeout: Timeout) {
    val (ref: ActorRef[U], future: Future[U], promiseRef: PromiseActorRef) =
      if (untyped.isTerminated)
        (
          adapter.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new AskTimeoutException(s"Recipient[$target] had already been terminated.")), null)
      else if (timeout.duration.length <= 0)
        (
          adapter.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new IllegalArgumentException(s"Timeout length must be positive, question not sent to [$target]")), null)
      else {
        val a = PromiseActorRef(untyped.provider, timeout, target, "unknown")
        val b = adapter.ActorRefAdapter[U](a)
        (b, a.result.future.asInstanceOf[Future[U]], a)
      }
  }

  private def askUntyped[T, U](target: ActorRef[T], untyped: InternalActorRef, timeout: Timeout, f: ActorRef[U] ⇒ T): Future[U] = {
    val p = new PromiseRef[U](target, untyped, timeout)
    val m = f(p.ref)
    if (p.promiseRef ne null) p.promiseRef.messageClassName = m.getClass.getName
    target ! m
    p.future
  }

  private def ask[T, U](actorRef: ActorRef[T], timeout: Timeout, scheduler: Scheduler, f: ActorRef[U] ⇒ T): Future[U] = {
    import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }
    val p = Promise[U]
    val ref = new FunctionRef[U](
      AskPath,
      (msg, self) ⇒ {
        p.trySuccess(msg)
        self.sendSystem(internal.Terminate())
      },
      (self) ⇒ if (!p.isCompleted) p.tryFailure(new NoSuchElementException("ask pattern terminated before value was received")))
    actorRef ! f(ref)
    val d = timeout.duration
    val c = scheduler.scheduleOnce(d)(p.tryFailure(new AskTimeoutException(s"did not receive message within $d")))(ec)
    val future = p.future
    future.andThen {
      case _ ⇒ c.cancel()
    }(ec)
  }

  private[typed] val AskPath = RootActorPath(Address("akka.typed.internal", "ask"))
}
