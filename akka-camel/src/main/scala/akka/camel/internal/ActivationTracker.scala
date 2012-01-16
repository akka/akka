package akka.camel.internal

import akka.actor._
import akka.camel._
import collection.mutable.WeakHashMap
import akka.event.Logging.Warning



class ActivationTracker extends Actor{

  val activations = new WeakHashMap[ActorRef,  ActivationStateMachine]

  class ActivationStateMachine {
    private[this] var awaitingActivation : List[ActorRef] = Nil
    private[this] var awaitingDeActivation : List[ActorRef] = Nil
    private[this] var activationFailure : Option[Throwable] = None

    var receive : Receive = notActivated

    def notActivated : Receive = {
      case AwaitActivation(ref) =>  awaitingActivation ::= sender
      case AwaitDeActivation(ref) => awaitingDeActivation ::= sender

      case msg @ EndpointActivated(ref)  => {
        migration.Migration.EventHandler.debug(ref+" activated")
        awaitingActivation.foreach(_ ! msg)
        awaitingActivation = Nil
        receive = activated
      }

      case EndpointFailedToActivate(ref, cause) => {
        migration.Migration.EventHandler.debug(ref+" failed to activate")
        activationFailure = Option(cause)
        awaitingActivation.foreach(_ ! EndpointFailedToActivate(ref, cause))
        awaitingActivation = Nil
        receive = failedToActivate
      }
    }

    def activated : Receive = {
      case AwaitActivation(ref) => sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) => awaitingDeActivation ::= sender
      case EndpointDeActivated(ref) => {
        awaitingDeActivation foreach (_ ! EndpointDeActivated(ref))
        awaitingDeActivation = Nil
        context.stop(self)
      }
      case msg : EndpointFailedToDeActivate => {
        awaitingDeActivation foreach (_ ! msg)
        awaitingDeActivation = Nil
      }
    }

    def failedToActivate : Receive = {
      case AwaitActivation(ref) => sender ! EndpointFailedToActivate(ref, activationFailure.get)
      case AwaitDeActivation(ref) => sender ! EndpointFailedToActivate(ref, activationFailure.get)
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
