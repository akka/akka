package akka.contrib.circuitbreaker

import akka.actor.{ ActorSelection, Actor, ActorRef }
import akka.contrib.circuitbreaker.CircuitBreakerProxy.CircuitOpenFailure
import akka.util.Timeout
import scala.language.implicitConversions

import scala.concurrent.{ ExecutionContext, Future }

final class OpenCircuitException extends Exception("Unable to complete operation since the Circuit Breaker Actor Proxy is in Open State")

/**
 * Convenience implicit conversions to provide circuit-breaker aware management of the ask pattern,
 * either directly replacing the `ask/?` with `askWithCircuitBreaker` or with an extension method to the
 * `Future` result of an `ask` pattern to fail in case of
 * [[akka.contrib.circuitbreaker.CircuitBreakerProxy.CircuitOpenFailure]] response
 */
object implicits {
  /**
   * Import this implicit to enable the methods `failForOpenCircuit` and `failForOpenCircuitWith`
   * to [[scala.concurrent.Future]] in order to handle
   * [[akka.contrib.circuitbreaker.CircuitBreakerProxy.CircuitOpenFailure]] failure responses throwing an
   * [[akka.contrib.circuitbreaker.OpenCircuitException]] or an exception  built with the given
   * exception builder
   */
  implicit def futureExtensions(future: Future[Any]) = new CircuitBreakerAwareFuture(future)

  /**
   * Import this implicit method to get an extended versions of the `ask` pattern for
   * [[akka.actor.ActorRef]] and [[akka.actor.ActorSelection]] in order to handle
   * [[akka.contrib.circuitbreaker.CircuitBreakerProxy.CircuitOpenFailure]] failure responses throwing an
   * [[akka.contrib.circuitbreaker.OpenCircuitException]]
   */
  implicit def askWithCircuitBreaker(actorRef: ActorRef) = new AskeableWithCircuitBreakerActor(actorRef)

  /**
   * This method is a wrapper of the [[akka.pattern.ask]] method
   * @throws akka.contrib.circuitbreaker.OpenCircuitException if the call failed because the circuit breaker proxy
   *                                                          state was OPEN
   */
  def askWithCircuitBreaker(circuitBreakerProxy: ActorRef, message: Any)(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Any] =
    circuitBreakerProxy.internalAskWithCircuitBreaker(message, timeout, ActorRef.noSender)

  /**
   * This method is a wrapper of the [[akka.pattern.ask]] method
   * @throws akka.contrib.circuitbreaker.OpenCircuitException if the call failed because the circuit breaker proxy
   *                                                          state was OPEN
   */
  def askWithCircuitBreaker(circuitBreakerProxy: ActorRef, message: Any, sender: ActorRef)(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Any] =
    circuitBreakerProxy.internalAskWithCircuitBreaker(message, timeout, sender)

}

/**
 * Extends [[scala.concurrent.Future]] with the method `failForOpenCircuitWith` to handle
 * [[akka.contrib.circuitbreaker.CircuitBreakerProxy.CircuitOpenFailure]] failure responses throwing
 * an exception built with the given exception builder
 */
class CircuitBreakerAwareFuture(val future: Future[Any]) extends AnyVal {
  def failForOpenCircuit(implicit executionContext: ExecutionContext): Future[Any] = failForOpenCircuitWith(new OpenCircuitException)

  def failForOpenCircuitWith(throwing: ⇒ Throwable)(implicit executionContext: ExecutionContext): Future[Any] = {
    future.flatMap {
      _ match {
        case CircuitOpenFailure(_) ⇒ Future.failed(throwing)
        case result                ⇒ Future.successful(result)
      }
    }
  }
}

final class AskeableWithCircuitBreakerActor(val actorRef: ActorRef) extends AnyVal {
  def askWithCircuitBreaker(message: Any)(implicit executionContext: ExecutionContext, timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAskWithCircuitBreaker(message, timeout, sender)

  private[circuitbreaker] def internalAskWithCircuitBreaker(message: Any, timeout: Timeout, sender: ActorRef)(implicit executionContext: ExecutionContext) = {
    import akka.pattern.ask
    import implicits.futureExtensions

    ask(actorRef, message, sender)(timeout).failForOpenCircuit
  }
}

final class AskeableWithCircuitBreakerActorSelection(val actorSelection: ActorSelection) extends AnyVal {
  def askWithCircuitBreaker(message: Any)(implicit executionContext: ExecutionContext, timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
    internalAskWithCircuitBreaker(message, timeout, sender)

  private[circuitbreaker] def internalAskWithCircuitBreaker(message: Any, timeout: Timeout, sender: ActorRef)(implicit executionContext: ExecutionContext) = {
    import akka.pattern.ask
    import implicits.futureExtensions

    ask(actorSelection, message, sender)(timeout).failForOpenCircuit
  }
}