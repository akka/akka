package akka.camel.internal

import akka.actor._
import akka.camel._
import collection.mutable.WeakHashMap
import akka.event.Logging.Warning


class ActivationTracker extends Actor{

  val activations = new WeakHashMap[ActorRef,  ActivationStateMachine]

  class ActivationStateMachine {
    type State = PartialFunction[ActivationMessage, Unit]

    var receive : State = notActivated()

    def notActivated() : State = {
      var awaitingActivation = List[ActorRef]()
      var awaitingDeActivation = List[ActorRef]()

      {
        case AwaitActivation(ref) =>  awaitingActivation ::= sender
        case AwaitDeActivation(ref) => awaitingDeActivation ::= sender

        case msg @ EndpointActivated(ref)  => {
          migration.Migration.EventHandler.debug(ref+" activated")
          awaitingActivation.foreach(_ ! msg)
          receive = activated(awaitingDeActivation)
        }

        case EndpointFailedToActivate(ref, cause) => {
          migration.Migration.EventHandler.debug(ref+" failed to activate")
          awaitingActivation.foreach(_ ! EndpointFailedToActivate(ref, cause))
          receive = failedToActivate(cause)
        }
      }
    }

    def activated( currentAwaitingDeActivation : List[ActorRef]) : State = {
      var awaitingDeActivation = currentAwaitingDeActivation

      {
        case AwaitActivation(ref) => sender ! EndpointActivated(ref)
        case AwaitDeActivation(ref) => awaitingDeActivation ::= sender
        case msg @ EndpointDeActivated(ref) =>  {
          awaitingDeActivation foreach (_ ! msg)
          receive = deactivated
        }
        case msg @ EndpointFailedToDeActivate(ref,  cause) =>  {
          awaitingDeActivation foreach (_ ! msg)
          receive = failedToDeActivate(cause)
        }
      }
    }

    def deactivated : State = {
      case AwaitActivation(ref) => sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) => sender ! EndpointDeActivated(ref)
    }

    def failedToActivate(cause:Throwable) : State = {
      case AwaitActivation(ref) => sender ! EndpointFailedToActivate(ref, cause)
      case AwaitDeActivation(ref) => sender ! EndpointFailedToActivate(ref, cause)
    }

    def failedToDeActivate(cause:Throwable) : State = {
      case AwaitActivation(ref) => sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) => sender ! EndpointFailedToDeActivate(ref, cause)
    }

  }

  override def preStart() {
    context.system.eventStream.subscribe(self, classOf[ActivationMessage])
  }

  override def receive = {
    case msg @ ActivationMessage(ref) =>{
      try{
        activations.getOrElseUpdate(ref, new ActivationStateMachine).receive(msg)
      }catch {
        //TODO use proper akka logging
        case e:MatchError => context.system.eventStream.publish(Warning("ActivationTracker",classOf[ActivationTracker], e)) //TODO: 1. Investigate proper logging; 2. Do we need want to log this?
      }
    }
  }
}

case class AwaitActivation(ref:ActorRef) extends ActivationMessage(ref)

case class AwaitDeActivation(ref : ActorRef) extends ActivationMessage(ref)