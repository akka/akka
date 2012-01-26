/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal

import akka.actor._
import collection.mutable.WeakHashMap

class ActivationTracker extends Actor with ActorLogging{
  val activations = new WeakHashMap[ActorRef, ActivationStateMachine]

  class ActivationStateMachine {
    type State = PartialFunction[ActivationMessage, Unit]

    var receive: State = notActivated()

    def notActivated(): State = {
      var awaitingActivation = List[ActorRef]()
      var awaitingDeActivation = List[ActorRef]()

      {
        case AwaitActivation(ref)   ⇒ awaitingActivation ::= sender
        case AwaitDeActivation(ref) ⇒ awaitingDeActivation ::= sender

        case msg @ EndpointActivated(ref) ⇒ {
          awaitingActivation.foreach(_ ! msg)
          receive = activated(awaitingDeActivation)
        }

        case EndpointFailedToActivate(ref, cause) ⇒ {
          awaitingActivation.foreach(_ ! EndpointFailedToActivate(ref, cause))
          receive = failedToActivate(cause)
        }
      }
    }

    def activated(currentAwaitingDeActivation: List[ActorRef]): State = {
      var awaitingDeActivation = currentAwaitingDeActivation

      {
        case AwaitActivation(ref)   ⇒ sender ! EndpointActivated(ref)
        case AwaitDeActivation(ref) ⇒ awaitingDeActivation ::= sender
        case msg @ EndpointDeActivated(ref) ⇒ {
          awaitingDeActivation foreach (_ ! msg)
          receive = deactivated
        }
        case msg @ EndpointFailedToDeActivate(ref, cause) ⇒ {
          awaitingDeActivation foreach (_ ! msg)
          receive = failedToDeActivate(cause)
        }
      }
    }

    def deactivated: State = {
      case AwaitActivation(ref)   ⇒ sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) ⇒ sender ! EndpointDeActivated(ref)
    }

    def failedToActivate(cause: Throwable): State = {
      case AwaitActivation(ref)   ⇒ sender ! EndpointFailedToActivate(ref, cause)
      case AwaitDeActivation(ref) ⇒ sender ! EndpointFailedToActivate(ref, cause)
    }

    def failedToDeActivate(cause: Throwable): State = {
      case AwaitActivation(ref)   ⇒ sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) ⇒ sender ! EndpointFailedToDeActivate(ref, cause)
    }

  }

  override def preStart() {
    context.system.eventStream.subscribe(self, classOf[ActivationMessage])
  }

  override def receive = {
    case msg @ ActivationMessage(ref) ⇒ {
      val state = activations.getOrElseUpdate(ref, new ActivationStateMachine)
      (state.receive orElse logStateWarning(ref))(msg)
    }
  }

  private[this] def logStateWarning(actorRef: ActorRef): Receive = { case msg ⇒ log.warning("Message [{}] not expected in current state of actor [{}]", msg, actorRef) }
}

case class AwaitActivation(ref: ActorRef) extends ActivationMessage(ref)

case class AwaitDeActivation(ref: ActorRef) extends ActivationMessage(ref)