package akka.camel

import internal._
import java.util.concurrent.TimeoutException
import akka.util.{Timeout, Duration}
import akka.dispatch.Future
import akka.actor.{ActorSystem, Props, ActorRef}

trait Activation{ this : Camel =>
  import akka.dispatch.Await

  val actorSystem : ActorSystem
  private[camel] val activationListener = actorSystem.actorOf(Props[ActivationTracker])

  //TODO we need better name for this
  def activationAwaitableFor(actor: ActorRef, timeout: Duration): Future[Unit] = {
    (activationListener ?(AwaitActivation(actor), Timeout(timeout))).map[Unit]{
      case EndpointActivated(_) => {}
      case EndpointFailedToActivate(_, cause) => throw cause
    }
  }

  /**
   * Awaits for actor to be activated.
   */

  def awaitActivation(actor: ActorRef, timeout: Duration){
    try{
      Await.result(activationAwaitableFor(actor, timeout), timeout)
    }catch {
      case e: TimeoutException => throw new ActivationTimeoutException
    }
  }

  //TODO we need better name for this
  def deactivationAwaitableFor(actor: ActorRef, timeout: Duration): Future[Unit] = {
    (activationListener ?(AwaitDeActivation(actor), Timeout(timeout))).map[Unit]{
      case EndpointDeActivated(_) => {}
      case EndpointFailedToDeActivate(_, cause) => throw cause
    }
  }

  def awaitDeactivation(actor: ActorRef, timeout: Duration) {
    try{
      Await.result(deactivationAwaitableFor(actor, timeout), timeout)
    }catch {
      case e: TimeoutException => throw new DeActivationTimeoutException
    }
  }

}

/**
 * Super class of all activation messages.
 */
private[camel] case class ActivationMessage(actor: ActorRef)

/**
 * Event message indicating that a single endpoint has been activated.
 */
private[camel] case class EndpointActivated(actorRef : ActorRef) extends ActivationMessage(actorRef)

private[camel] case class EndpointFailedToActivate(actorRef : ActorRef, cause : Throwable) extends ActivationMessage(actorRef)

private[camel] case class EndpointDeActivated(actorRef : ActorRef) extends ActivationMessage(actorRef)

private[camel] case class EndpointFailedToDeActivate(actorRef : ActorRef, cause : Throwable) extends ActivationMessage(actorRef)


/**
 * Event message asking the endpoint to respond with EndpointActivated message when it gets activated.
 */
case class AwaitActivation(ref:ActorRef) extends ActivationMessage(ref)
case class AwaitDeActivation(ref : ActorRef) extends ActivationMessage(ref)

class DeActivationTimeoutException extends RuntimeException("Timed out while waiting for de-activation.")
class ActivationTimeoutException extends RuntimeException("Timed out while waiting for activation.")
