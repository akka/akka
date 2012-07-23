/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import akka.actor._
import akka.util.{ Timeout }
import akka.dispatch.{ Unwatch, Watch }
import scala.concurrent.Future
import scala.concurrent.util.Duration

trait GracefulStopSupport {
  /**
   * Returns a [[scala.concurrent.Future]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors,
   * which should only be done outside of the ActorSystem as blocking inside Actors is discouraged.
   *
   * <b>IMPORTANT NOTICE:</b> the actor being terminated and its supervisor
   * being informed of the availability of the deceased actor’s name are two
   * distinct operations, which do not obey any reliable ordering. Especially
   * the following will NOT work:
   *
   * {{{
   * def receive = {
   *   case msg =>
   *     Await.result(gracefulStop(someChild, timeout), timeout)
   *     context.actorOf(Props(...), "someChild") // assuming that that was someChild’s name, this will NOT work
   * }
   * }}}
   *
   * If the target actor isn't terminated within the timeout the [[scala.concurrent.Future]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: Duration)(implicit system: ActorSystem): Future[Boolean] = {
    if (target.isTerminated) Future successful true
    else system match {
      case e: ExtendedActorSystem ⇒
        import e.dispatcher // TODO take implicit ExecutionContext/MessageDispatcher in method signature?
        val internalTarget = target.asInstanceOf[InternalActorRef]
        val ref = PromiseActorRef(e.provider, Timeout(timeout))
        internalTarget.sendSystemMessage(Watch(target, ref))
        val f = ref.result.future
        f onComplete { // Just making sure we're not leaking here
          case Right(Terminated(`target`)) ⇒ ()
          case _                           ⇒ internalTarget.sendSystemMessage(Unwatch(target, ref))
        }
        target ! PoisonPill
        f map {
          case Terminated(`target`) ⇒ true
          case _                    ⇒ false
        }
      case s ⇒ throw new IllegalArgumentException("Unknown ActorSystem implementation: '" + s + "'")
    }
  }
}