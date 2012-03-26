/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal._
import akka.util.{ Timeout, Duration }
import akka.dispatch.Future
import java.util.concurrent.TimeoutException
import akka.actor.{ ActorSystem, Props, ActorRef }
import akka.pattern._

/**
 * Activation trait that can be used to wait on activation or de-activation of Camel endpoints.
 * The Camel endpoints are activated asynchronously. This trait can signal when an endpoint is activated or de-activated.
 */
trait Activation {
  import akka.dispatch.Await

  def system: ActorSystem

  private val activationTracker = system.actorOf(Props[ActivationTracker])

  /**
   * Awaits for endpoint to be activated. It blocks until the endpoint is registered in camel context or timeout expires.
   * @param endpoint the endpoint to wait for to be activated
   * @param timeout the timeout for the wait
   * @throws akka.camel.ActivationTimeoutException if endpoint is not activated within timeout.
   * @return the activated ActorRef
   */
  def awaitActivation(endpoint: ActorRef, timeout: Duration): ActorRef = {
    try {
      Await.result(activationFutureFor(endpoint, timeout), timeout)
    } catch {
      case e: TimeoutException ⇒ throw new ActivationTimeoutException(endpoint, timeout)
    }
  }

  /**
   * Awaits for endpoint to be de-activated. It is blocking until endpoint is unregistered in camel context or timeout expires.
   * @param endpoint the endpoint to wait for to be de-activated
   * @param timeout the timeout for the wait
   * @throws akka.camel.DeActivationTimeoutException if endpoint is not de-activated within timeout.
   */
  def awaitDeactivation(endpoint: ActorRef, timeout: Duration) {
    try {
      Await.result(deactivationFutureFor(endpoint, timeout), timeout)
    } catch {
      case e: TimeoutException ⇒ throw new DeActivationTimeoutException(endpoint, timeout)
    }
  }

  /**
   * Similar to `awaitActivation` but returns a future instead.
   * @param endpoint the endpoint to be activated
   * @param timeout the timeout for the Future
   */
  def activationFutureFor(endpoint: ActorRef, timeout: Duration): Future[ActorRef] = {
    (activationTracker.ask(AwaitActivation(endpoint))(Timeout(timeout))).map[ActorRef] {
      case EndpointActivated(_)               ⇒ endpoint
      case EndpointFailedToActivate(_, cause) ⇒ throw cause
    }
  }

  /**
   * Similar to awaitDeactivation but returns a future instead.
   * @param endpoint the endpoint to be deactivated
   * @param timeout the timeout of the Future
   */
  def deactivationFutureFor(endpoint: ActorRef, timeout: Duration): Future[Unit] = {
    (activationTracker.ask(AwaitDeActivation(endpoint))(Timeout(timeout))).map[Unit] {
      case EndpointDeActivated(_)               ⇒ ()
      case EndpointFailedToDeActivate(_, cause) ⇒ throw cause
    }
  }
}

/**
 * An exception for when a timeout has occurred during deactivation of an endpoint.
 * @param endpoint the endpoint that could not be de-activated in time
 * @param timeout the timeout
 */
class DeActivationTimeoutException(endpoint: ActorRef, timeout: Duration) extends TimeoutException {
  override def getMessage = "Timed out after %s, while waiting for de-activation of %s" format (timeout, endpoint.path)
}

/**
 * An exception for when a timeout has occurred during the activation of an endpoint.
 * @param endpoint the endpoint that could not be activated in time
 * @param timeout the timeout
 */
class ActivationTimeoutException(endpoint: ActorRef, timeout: Duration) extends TimeoutException {
  override def getMessage = "Timed out after %s, while waiting for activation of %s" format (timeout, endpoint.path)
}