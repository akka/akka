package akka.camel.internal

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.actor.ActorRef

/**
 * Super class of all activation messages. Registration of the Camel [[akka.camel.Consumer]]s and [[akka.camel.Producer]]s
 * is done asynchronously. Activation messages are sent in the Camel extension when endpoints are
 * activated, de-activated, failed to activate and failed to de-activate.
 * You can use the [[akka.camel.Activation]] trait which is available on [[akka.camel.Camel]]
 * to await activation or de-activation of endpoints.
 */
private[camel] abstract class ActivationMessage(val actor: ActorRef)

/**
 * For internal use only. companion object of <code>ActivationMessage</code>
 *
 */
private[camel] object ActivationMessage {
  def unapply(msg: ActivationMessage): Option[ActorRef] = Some(msg.actor)
}

/**
 * Event message indicating that a single endpoint has been activated.
 * You can use the [[akka.camel.Activation]] trait which is available on [[akka.camel.Camel]]
 * to await activation or de-activation of endpoints.
 * @param actorRef the endpoint that was activated
 */
private[camel] sealed case class EndpointActivated(actorRef: ActorRef) extends ActivationMessage(actorRef)

/**
 * Event message indicating that a single endpoint failed to activate.
 * You can use the [[akka.camel.Activation]] trait which is available on [[akka.camel.Camel]]
 * to await activation or de-activation of endpoints.
 * @param actorRef the endpoint that failed to activate
 * @param cause the cause for failure
 */
private[camel] sealed case class EndpointFailedToActivate(actorRef: ActorRef, cause: Throwable) extends ActivationMessage(actorRef)

/**
 * Event message indicating that a single endpoint was de-activated.
 * You can use the [[akka.camel.Activation]] trait which is available on [[akka.camel.Camel]]
 * to await activation or de-activation of endpoints.
 * @param actorRef the endpoint that was de-activated
 */
private[camel] sealed case class EndpointDeActivated(actorRef: ActorRef) extends ActivationMessage(actorRef)

/**
 * Event message indicating that a single endpoint failed to de-activate.
 * You can use the [[akka.camel.Activation]] trait which is available on [[akka.camel.Camel]]
 * to await activation or de-activation of endpoints.
 * @param actorRef the endpoint that failed to de-activate
 * @param cause the cause for failure
 */
private[camel] sealed case class EndpointFailedToDeActivate(actorRef: ActorRef, cause: Throwable) extends ActivationMessage(actorRef)
