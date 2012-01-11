package akka.camel


import akka.actor._
import collection.mutable.WeakHashMap

object ActivationAware{

  /**
   * Awaits for actor to be de-activated.
   */
}

class DeActivationTimeoutException extends RuntimeException("Timed out while waiting for de-activation. Please make sure your actor extends ActivationAware trait.")
class ActivationTimeoutException extends RuntimeException("Timed out while waiting for activation. Please make sure your actor extends ActivationAware trait.")


class ActivationListener extends Actor{

   //TODO handle state loss on restart (IMPORTANT!!!)
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
      case _ => {/*ignore*/}
    }

    def failedToActivate : Receive = {
      case AwaitActivation(ref) => sender ! EndpointFailedToActivate(ref, activationFailure.get)
      case AwaitDeActivation(ref) => sender ! EndpointFailedToActivate(ref, activationFailure.get)
      case _ => {/*ignore*/}
    }

    def notActivated : Receive = {
      case AwaitActivation(ref) =>  awaitingActivation ::= sender
      case AwaitDeActivation(ref) => awaitingDeActivation ::= sender

      case EndpointActivated(ref)  => {
        migration.Migration.EventHandler.debug(ref+" activated")
        awaitingActivation.foreach(_ ! EndpointActivated(ref))
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
      case _ => {/*ignore*/}
    }

    def onDeactivation(ref: ActorRef){
      awaitingDeActivation foreach (_ ! EndpointDeActivated(ref))
      awaitingDeActivation = Nil
      context.stop(self)
    }
  }

  //TODO check if subscribe is idempotent (in case of restart)
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

