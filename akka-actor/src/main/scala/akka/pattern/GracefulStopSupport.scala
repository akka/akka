/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import akka.actor.{ ActorRef, Actor, ActorSystem, Props, PoisonPill, Terminated, ReceiveTimeout, ActorTimeoutException }
import akka.dispatch.{ Promise, Future }
import akka.util.Duration

trait GracefulStopSupport {
  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with success (value `true`) when
   * existing messages of the target actor has been processed and the actor has been
   * terminated.
   *
   * Useful when you need to wait for termination or compose ordered termination of several actors.
   *
   * If the target actor isn't terminated within the timeout the [[akka.dispatch.Future]]
   * is completed with failure [[akka.actor.ActorTimeoutException]].
   */
  def gracefulStop(target: ActorRef, timeout: Duration)(implicit system: ActorSystem): Future[Boolean] = {
    if (target.isTerminated) {
      Promise.successful(true)
    } else {
      val result = Promise[Boolean]()
      system.actorOf(Props(new Actor {
        // Terminated will be received when target has been stopped
        context watch target
        target ! PoisonPill
        // ReceiveTimeout will be received if nothing else is received within the timeout
        context setReceiveTimeout timeout

        def receive = {
          case Terminated(a) if a == target ⇒
            result success true
            context stop self
          case ReceiveTimeout ⇒
            result failure new ActorTimeoutException(
              "Failed to stop [%s] within [%s]".format(target.path, context.receiveTimeout), system)
            context stop self
        }
      }))
      result
    }
  }
}