package akka.camel


import akka.actor._
import akka.dispatch.Await
import akka.util.{Timeout, Duration}
import java.util.concurrent.TimeoutException

object ActivationAware{

  /**
   * Awaits for actor to be activated.
   */
  def awaitActivation(actor: ActorRef, timeout: Duration) = {
    implicit val timeout2 = Timeout(timeout)
    try{
      Await.result(actor ? AwaitActivation, timeout) match {
        case EndpointActivated(_) => {}
        case EndpointFailedToActivate(cause) => throw cause
        case msg => throw new RuntimeException("Expected EndpointActivated message but got "+msg)
      }
    }catch {
      case e: TimeoutException => throw new ActivationTimeoutException
    }
  }
  /**
   * Awaits for actor to be de-activated.
   */
  def registerInterestInDeActivation(actor: ActorRef, timeout: Duration): () => Unit = {
    val awaitable = actor ? (AwaitDeActivation, Timeout(timeout))

    () => try{
      Await.result(awaitable, timeout) match {
        case EndpointDeActivated(_) => {}
        case msg => throw new RuntimeException("Expected EndpointActivated message but got "+msg)
      }
    }catch {
      case e: TimeoutException => throw new DeActivationTimeoutException
    }
  }
}

class DeActivationTimeoutException extends RuntimeException("Timed out while waiting for de-activation. Please make sure your actor extends ActivationAware trait.")
class ActivationTimeoutException extends RuntimeException("Timed out while waiting for activation. Please make sure your actor extends ActivationAware trait.")

trait ActivationAware extends Actor with CamelEndpoint {
  val stateMachine = new ActivationStateMachine


  class ActivationStateMachine{ //TODO: I tried to implement this as an actor but I couldn't start it from preStart. Any ideas?
    private[this] var awaitingActivation : List[ActorRef] = Nil
    private[this] var awaitingDeActivation : List[ActorRef] = Nil
    private[this] var activationFailure : Option[Throwable] = None

    var receive : Receive = notActivated

    def activated : Receive = {
      case AwaitActivation => sender ! EndpointActivated(self)
      case AwaitDeActivation => awaitingDeActivation ::= sender
    }

    def failedToActivate : Receive = {
      case AwaitActivation => sender ! EndpointFailedToActivate(activationFailure.get)
      case AwaitDeActivation => sender ! EndpointFailedToActivate(activationFailure.get)
    }

    def notActivated : Receive = {
      case AwaitActivation =>  awaitingActivation ::= sender
      case AwaitDeActivation => awaitingDeActivation ::= sender

      case EndpointActivated(ref) => {
        migration.Migration.EventHandler.debug(this+" activated")
        awaitingActivation.foreach(_ ! EndpointActivated(ref))
        awaitingActivation = Nil
        receive = activated
      }

      case EndpointFailedToActivate(cause) => {
        migration.Migration.EventHandler.debug(this+" failed to activate")
        activationFailure = Option(cause)
        awaitingActivation.foreach(_ ! EndpointFailedToActivate(cause))
        awaitingActivation = Nil
        receive = failedToActivate
      }
    }

    def onDeactivation(){
      awaitingDeActivation foreach (_ ! EndpointDeActivated(self))
      awaitingDeActivation = Nil
    }
  }



  override def preStart {
    super.preStart()
    context.become(forwardToHelper orElse receive, true)
  }

  def forwardToHelper : Receive = {
    case msg if (msg.isInstanceOf[ActivationMessage])  =>{
//      println("GOT "+msg)
      stateMachine.receive(msg)
    }
  }

  override def postDeactivation() {
    super.postDeactivation()
    stateMachine.onDeactivation()
  }

  //TODO: consider adding postActivation method to be symetric with de-activation

}
trait ActivationMessage

/**
 * Event message asking the endpoint to respond with EndpointActivated message when it gets activated
 */
object AwaitActivation extends ActivationMessage
object AwaitDeActivation extends ActivationMessage

