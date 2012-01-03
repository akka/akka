/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.dispatch.Future
import akka.util.Duration

/**
 * Patterns is the Java API for the Akka patterns that provide solutions
 * to commonly occurring problems.
 */
object Patterns {

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
  def gracefulStop(target: ActorRef, timeout: Duration, system: ActorSystem): Future[java.lang.Boolean] = {
    akka.pattern.gracefulStop(target, timeout)(system).asInstanceOf[Future[java.lang.Boolean]]
  }
}