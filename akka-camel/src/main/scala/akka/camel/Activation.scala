/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.camel.internal._
import akka.util.Timeout
import akka.actor.{ ActorSystem, Props, ActorRef }
import akka.pattern._
import scala.concurrent.util.Duration
import concurrent.{ ExecutionContext, Future }

/**
 * Activation trait that can be used to wait on activation or de-activation of Camel endpoints.
 * The Camel endpoints are activated asynchronously. This trait can signal when an endpoint is activated or de-activated.
 */
trait Activation {

  /**
   * Produces a Future with the specified endpoint that will be completed when the endpoint has been activated,
   * or if it times out, which will happen after the specified Timeout.
   *
   * @param endpoint the endpoint to be activated
   * @param timeout the timeout for the Future
   */
  def activationFutureFor(endpoint: ActorRef)(implicit timeout: Duration, executor: ExecutionContext): Future[ActorRef]

  /**
   * Produces a Future which will be completed when the given endpoint has been deactivated or
   * or if it times out, which will happen after the specified Timeout.
   *
   * @param endpoint the endpoint to be deactivated
   * @param timeout the timeout of the Future
   */
  def deactivationFutureFor(endpoint: ActorRef)(implicit timeout: Duration, executor: ExecutionContext): Future[ActorRef]
}