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
        case EndpointActivated => {}
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
        case EndpointDeActivated => {}
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
  private[this] var awaitingActivation : List[ActorRef] = Nil
  private[this] var awaitingDeActivation : List[ActorRef] = Nil
  private[this] var activated = false


  override def preStart {
    super.preStart()
    context.become(activation orElse receive, true)
  }

  override def postDeactivation() {
    super.postDeactivation()
    awaitingDeActivation foreach (_ ! EndpointDeActivated)
    awaitingDeActivation = Nil
  }

  //TODO: consider adding postActivation method to be symetric with de-activation

  def activation : Receive = {
    case AwaitActivation => if (activated) sender ! EndpointActivated else awaitingActivation ::= sender
    case AwaitDeActivation => awaitingDeActivation ::= sender
    case EndpointActivated => {
      migration.Migration.EventHandler.debug(this+" activated")
      activated = true
      awaitingActivation.foreach(_ ! EndpointActivated)
      awaitingActivation = Nil
    }
  }
}

/**
 * Event message asking the endpoint to respond with EndpointActivated message when it gets activated
 */
object AwaitActivation
object AwaitDeActivation

