package akka.camel


import akka.actor._
import collection.mutable.WeakHashMap

class DeActivationTimeoutException extends RuntimeException("Timed out while waiting for de-activation.")
class ActivationTimeoutException extends RuntimeException("Timed out while waiting for activation.")


class ActivationListener extends Actor{

  val activations = new WeakHashMap[ActorRef,  ActivationStateMachine]

  class ActivationStateMachine {
    private[this] var awaitingActivation : List[ActorRef] = Nil
    private[this] var awaitingDeActivation : List[ActorRef] = Nil
    private[this] var activationFailure : Option[Throwable] = None

    var receive : Receive = notActivated

    def activated : Receive = {
      case AwaitActivation(ref) => sender ! EndpointActivated(ref)
      case AwaitDeActivation(ref) => awaitingDeActivation ::= sender
      case EndpointDeActivated(ref) => onDeactivation(ref)
      case msg : EndpointFailedToDeActivate => {
        awaitingDeActivation foreach (_ ! msg)
        awaitingDeActivation = Nil
      }
      case _ => {}
    }

    def failedToActivate : Receive = {
      case AwaitActivation(ref) => sender ! EndpointFailedToActivate(ref, activationFailure.get)
      case AwaitDeActivation(ref) => sender ! EndpointFailedToActivate(ref, activationFailure.get)
      case _ => {}
    }

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


    def onDeactivation(ref: ActorRef){
      awaitingDeActivation foreach (_ ! EndpointDeActivated(ref))
      awaitingDeActivation = Nil
      context.stop(self)
    }
  }

  context.system.eventStream.subscribe(self, classOf[ActivationMessage])


  override def receive = {
    case msg @ ActivationMessage(ref) =>{
      activations.getOrElseUpdate(ref, new ActivationStateMachine).receive(msg)
    }
  }


}
case class ActivationMessage(actor: ActorRef)

/**
 * Event message asking the endpoint to respond with EndpointActivated message when it gets activated
 */
case class AwaitActivation(ref:ActorRef) extends ActivationMessage(ref)
case class AwaitDeActivation(ref : ActorRef) extends ActivationMessage(ref)

