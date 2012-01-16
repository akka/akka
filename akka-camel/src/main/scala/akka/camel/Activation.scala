package akka.camel

import internal._
import java.util.concurrent.TimeoutException
import akka.util.{Timeout, Duration}
import akka.dispatch.Future
import akka.actor.{ActorSystem, Props, ActorRef}

trait Activation{
  import akka.dispatch.Await

  val actorSystem : ActorSystem
  private[camel] val activationTracker = actorSystem.actorOf(Props[ActivationTracker])

  def activationFutureFor(actor: ActorRef, timeout: Duration): Future[ActorRef] = {
    (activationTracker ?(AwaitActivation(actor), Timeout(timeout))).map[ActorRef]{
      case EndpointActivated(_) => actor
      case EndpointFailedToActivate(_, cause) => throw cause
    }
  }

  /**
   * Awaits for actor to be activated.
   */
  def awaitActivation(actor: ActorRef, timeout: Duration): ActorRef = {
    try{
      Await.result(activationFutureFor(actor, timeout), timeout)
    }catch {
      case e: TimeoutException => throw new ActivationTimeoutException
    }
  }

  def deactivationFutureFor(actor: ActorRef, timeout: Duration): Future[Unit] = {
    (activationTracker ?(AwaitDeActivation(actor), Timeout(timeout))).map[Unit]{
      case EndpointDeActivated(_) => {}
      case EndpointFailedToDeActivate(_, cause) => throw cause
    }
  }

  def awaitDeactivation(actor: ActorRef, timeout: Duration) {
    try{
      Await.result(deactivationFutureFor(actor, timeout), timeout)
    }catch {
      case e: TimeoutException => throw new DeActivationTimeoutException
    }
  }

}




class DeActivationTimeoutException extends RuntimeException("Timed out while waiting for de-activation.")
class ActivationTimeoutException extends RuntimeException("Timed out while waiting for activation.")
