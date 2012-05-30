/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import akka.actor._
import akka.util.{ Timeout, Duration }
import akka.dispatch.{ Unwatch, Watch, Promise, Future }

trait GracefulStopSupport {
  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with success (value `true`) when
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
   * If the target actor isn't terminated within the timeout the [[akka.dispatch.Future]]
   * is completed with failure [[akka.pattern.AskTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: Duration)(implicit system: ActorSystem): Future[Boolean] = {
    if (target.isTerminated) Promise.successful(true)
    else system match {
      case e: ExtendedActorSystem ⇒
        val internalTarget = target.asInstanceOf[InternalActorRef]
        val ref = PromiseActorRef(e.provider, Timeout(timeout))
        internalTarget.sendSystemMessage(Watch(target, ref))
        val result = ref.result map {
          case Terminated(`target`) ⇒ true
          case _                    ⇒ internalTarget.sendSystemMessage(Unwatch(target, ref)); false // Just making sure we're not leaking here
        }
        target ! PoisonPill
        result
      case s ⇒ throw new IllegalArgumentException("Unknown ActorSystem implementation: '" + s + "'")
    }
  }
}