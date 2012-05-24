/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal

import akka.actor._
import collection.mutable.WeakHashMap
import akka.camel._

/**
 * For internal use only. An actor that tracks activation and de-activation of endpoints.
 */
private[akka] final class ActivationTracker extends Actor with ActorLogging {
  val activations = new WeakHashMap[ActorRef, ActivationStateMachine]

  /**
   * A state machine that keeps track of the endpoint activation status of an actor.
   */
  class ActivationStateMachine {
    type State = PartialFunction[ActivationMessage, Unit]

    var receive: State = notActivated()

    /**
     * Not activated state
     * @return a partial function that handles messages in the 'not activated' state
     */
    def notActivated(): State = {
      var awaitingActivation = List[ActorRef]()
      var awaitingDeActivation = List[ActorRef]()

      {
        case AwaitActivation(ref)   ⇒ awaitingActivation ::= sender
        case AwaitDeActivation(ref) ⇒ awaitingDeActivation ::= sender
        case msg @ EndpointActivated(ref) ⇒
          awaitingActivation.foreach(_ ! msg)
          receive = activated(awaitingDeActivation)
        case EndpointFailedToActivate(ref, cause) ⇒
          awaitingActivation.foreach(_ ! EndpointFailedToActivate(ref, cause))
          receive = failedToActivate(cause)
      }
    }

    /**
     * Activated state.
     * @param currentAwaitingDeActivation the current <code>ActorRef</code>s awaiting de-activation
     * @return a partial function that handles messages in the 'activated' state
     */
    def activated(currentAwaitingDeActivation: List[ActorRef]): State = {
      var awaitingDeActivation = currentAwaitingDeActivation

      {
        case AwaitActivation(ref)   ⇒ sender ! EndpointActivated(ref)
        case AwaitDeActivation(ref) ⇒ awaitingDeActivation ::= sender
        case msg @ EndpointDeActivated(ref) ⇒
          awaitingDeActivation foreach (_ ! msg)
          receive = deactivated
        case msg @ EndpointFailedToDeActivate(ref, cause) ⇒
          awaitingDeActivation foreach (_ ! msg)
          receive = failedToDeActivate(cause)
      }
    }

    /**
     * De-activated state
     * @return a partial function that handles messages in the 'de-activated' state
     */
    def deactivated: State = {
      case AwaitActivation(ref)   ⇒ sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) ⇒ sender ! EndpointDeActivated(ref)
    }

    /**
     * Failed to activate state
     * @param cause the cause for the failure
     * @return a partial function that handles messages in 'failed to activate' state
     */
    def failedToActivate(cause: Throwable): State = {
      case AwaitActivation(ref)   ⇒ sender ! EndpointFailedToActivate(ref, cause)
      case AwaitDeActivation(ref) ⇒ sender ! EndpointFailedToActivate(ref, cause)
    }

    /**
     * Failed to de-activate state
     * @param cause the cause for the failure
     * @return a partial function that handles messages in 'failed to de-activate' state
     */
    def failedToDeActivate(cause: Throwable): State = {
      case AwaitActivation(ref)   ⇒ sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) ⇒ sender ! EndpointFailedToDeActivate(ref, cause)
    }

  }

  /**
   * Subscribes self to messages of type <code>ActivationMessage</code>
   */
  override def preStart(): Unit = context.system.eventStream.subscribe(self, classOf[ActivationMessage])

  override def receive = {
    case msg @ ActivationMessage(ref) ⇒
      (activations.getOrElseUpdate(ref, new ActivationStateMachine).receive orElse logStateWarning(ref))(msg)
  }

  private[this] def logStateWarning(actorRef: ActorRef): Receive =
    { case msg ⇒ log.warning("Message [{}] not expected in current state of actor [{}]", msg, actorRef) }
}

/**
 * For internal use only. A request message to the ActivationTracker for the status of activation.
 * @param ref the actorRef
 */
private[camel] case class AwaitActivation(ref: ActorRef) extends ActivationMessage(ref)

/**
 * For internal use only. A request message to the ActivationTracker for the status of de-activation.
 * For internal use only.
 * @param ref the actorRef
 */
private[camel] case class AwaitDeActivation(ref: ActorRef) extends ActivationMessage(ref)