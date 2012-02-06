/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal._
import akka.util.{ Timeout, Duration }
import akka.dispatch.Future
import java.util.concurrent.TimeoutException
import akka.actor.{ ActorSystem, Props, ActorRef }

trait Activation {
  import akka.dispatch.Await

  def system: ActorSystem
  private[camel] val activationTracker = system.actorOf(Props[ActivationTracker])

  /**
   * Awaits for endpoint to be activated. It blocks until the endpoint is registered in camel context or timeout expires.
   *
   * @throws akka.camel.ActivationTimeoutException if endpoint is not activated within timeout.
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
   *
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
   * Similar to `awaitActivation` but returns future instead.
   */
  def activationFutureFor(endpoint: ActorRef, timeout: Duration): Future[ActorRef] = {
    (activationTracker ? (AwaitActivation(endpoint), Timeout(timeout))).map[ActorRef] {
      case EndpointActivated(_)               ⇒ endpoint
      case EndpointFailedToActivate(_, cause) ⇒ throw cause
    }
  }

  /**
   * Similar to awaitDeactivation but returns future instead.
   */
  def deactivationFutureFor(endpoint: ActorRef, timeout: Duration): Future[Unit] = {
    (activationTracker ? (AwaitDeActivation(endpoint), Timeout(timeout))).map[Unit] {
      case EndpointDeActivated(_)               ⇒ {}
      case EndpointFailedToDeActivate(_, cause) ⇒ throw cause
    }
  }
}

class DeActivationTimeoutException(endpoint: ActorRef, timeout: Duration) extends TimeoutException {
  override def getMessage = "Timed out after %s, while waiting for de-activation of %s" format (timeout, endpoint.path)
}

class ActivationTimeoutException(endpoint: ActorRef, timeout: Duration) extends TimeoutException {
  override def getMessage = "Timed out after %s, while waiting for activation of %s" format (timeout, endpoint.path)
}