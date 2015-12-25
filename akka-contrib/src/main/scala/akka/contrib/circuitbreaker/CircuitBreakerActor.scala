/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.circuitbreaker

import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object CircuitBreakerActor {

  /**
   * Creates an circuit breaker actor proxying a target actor intended for request-reply interactions.
   * It is possible to send messages through this proxy without expecting a response wrapping them into a
   * [[akka.contrib.circuitbreaker.CircuitBreakerActor.TellOnly]]
   *
   * The circuit breaker implements the same state machine documented in [[akka.pattern.CircuitBreaker]]
   *
   * @param target the actor to proxy
   * @param maxFailures maximum number of failures before opening the circuit
   * @param callTimeout timeout before considering the ongoing call a failure
   * @param resetTimeout time after which the channel will be closed after entering the open state
   * @param circuitEventListener an actor that will receive a series of messages of type
   *                             [[akka.contrib.circuitbreaker.CircuitBreakerActor.CircuitBreakerEvent]]
   * @param failureDetector  function to detect if the a message received from the target actor as
   *                         response from a request represent a failure
   * @param failureMap   function to map a failure into a response message. The failing response message is wrapped
   *                     into a [[akka.contrib.circuitbreaker.CircuitBreakerActor.CircuitOpenFailure]] object
   */
  def props(target: ActorRef,
            maxFailures: Int,
            callTimeout: Timeout,
            resetTimeout: Timeout,
            circuitEventListener: Option[ActorRef],
            failureDetector: Any ⇒ Boolean,
            failureMap: CircuitOpenFailure ⇒ Any) =
    Props(new CircuitBreakerActor(target, maxFailures, callTimeout, resetTimeout, circuitEventListener, failureDetector, failureMap))

  sealed trait CircuitBreakerCommand

  final case class TellOnly(msg: Any) extends CircuitBreakerCommand

  sealed trait CircuitBreakerResponse
  final case class CircuitOpenFailure(failedMsg: Any)

  sealed trait CircuitBreakerEvent
  final case class CircuitOpen(circuit: ActorRef) extends CircuitBreakerCommand
  final case class CircuitClosed(circuit: ActorRef) extends CircuitBreakerCommand
  final case class CircuitHalfOpen(circuit: ActorRef) extends CircuitBreakerCommand

  sealed trait CircuitBreakerState
  case object Open extends CircuitBreakerState
  case object Closed extends CircuitBreakerState
  case object HalfOpen extends CircuitBreakerState

  final case class CircuitBreakerStateData(failureCount: Int = 0, firstHalfOpenMessageSent: Boolean = false)

  final case class CircuitBreakerActorBuilder(
    maxFailures: Int, callTimeout: Timeout, resetTimeout: Timeout,
    circuitEventListener: Option[ActorRef] = None,
    failureDetector: Any ⇒ Boolean = { _ ⇒ false },
    openCircuitFailureConverter: CircuitOpenFailure ⇒ Any = identity) {

    /**
     * Creates the props for a [[akka.contrib.circuitbreaker.CircuitBreakerActor]] proxying the given target
     *
     * @param target the target actor ref
     */
    def propsForTarget(target: ActorRef) = CircuitBreakerActor.props(target, maxFailures, callTimeout, resetTimeout, circuitEventListener, failureDetector, openCircuitFailureConverter)

  }

  class OpenCircuitException extends Exception("Circuit Open so unable to complete operation")

  /**
   * Extends [[scala.concurrent.Future]] with the method failForOpenCircuitWith to handle
   *   [[akka.contrib.circuitbreaker.CircuitBreakerActor.CircuitOpenFailure]] failure responses throwing
   *   an exception built with the given exception builder
   */
  implicit class CircuitBreakerAwareFuture(val future: Future[Any]) extends AnyVal {
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

}

object CircuitBreakerInternalEvents {
  sealed trait CircuitBreakerInternalEvent
  case object CallFailed extends CircuitBreakerInternalEvent
  case object CallSucceeded extends CircuitBreakerInternalEvent
}

import CircuitBreakerActor._
import CircuitBreakerInternalEvents._

class CircuitBreakerActor(
  target: ActorRef,
  maxFailures: Int,
  callTimeout: Timeout,
  resetTimeout: Timeout,
  circuitEventListener: Option[ActorRef],
  failureDetector: Any ⇒ Boolean,
  failureMap: CircuitOpenFailure ⇒ Any) extends Actor with ActorLogging with FSM[CircuitBreakerState, CircuitBreakerStateData] {

  startWith(Closed, CircuitBreakerStateData(failureCount = 0))

  def callSucceededHandling: StateFunction = {
    case Event(CallSucceeded, state) ⇒
      log.debug("Received call succeeded notification in state {} resetting counter", state)
      goto(Closed) using CircuitBreakerStateData(failureCount = 0, firstHalfOpenMessageSent = false)
  }

  when(Closed) {
    callSucceededHandling orElse {
      case Event(TellOnly(message), _) ⇒
        log.debug("CLOSED: Sending message {} without expecting any response", message)
        target ! message
        stay

      case Event(CallFailed, state) ⇒
        log.debug("Received call failed notification in state {} incrementing counter", state)
        val newState = state.copy(failureCount = state.failureCount + 1)
        if (newState.failureCount < maxFailures) {
          stay using newState
        } else {
          goto(Open) using newState
        }

      case Event(message, state) ⇒
        log.debug("CLOSED: Sending message {} expecting a response withing timeout {}", message, callTimeout)
        val currentSender = sender()
        forwardRequest(message, sender, state, log)
        stay

    }
  }

  when(Open, stateTimeout = resetTimeout.duration) {
    callSucceededHandling orElse {
      case Event(StateTimeout, state) ⇒
        log.debug("Timeout expired for state OPEN, going to half open")
        goto(HalfOpen) using state.copy(firstHalfOpenMessageSent = false)

      case Event(CallFailed, state) ⇒
        log.debug("OPEN: Call received a further call failed notification, probably from a previous timed out event, ignoring")
        stay

      case Event(openNotification @ CircuitOpenFailure(_), _) ⇒
        log.error("Why did I send message {} to myself?", openNotification)
        stay

      case Event(message, state) ⇒
        val failureNotification = failureMap(CircuitOpenFailure(message))
        log.debug("OPEN: Failing request for message {}, sending failure notification {} to sender {}", message, failureNotification, sender)
        sender ! failureNotification
        stay

    }
  }

  when(HalfOpen) {
    callSucceededHandling orElse {
      case Event(TellOnly(message), _) ⇒
        log.debug("HALF-OPEN: Dropping TellOnly request for message {}", message)
        stay

      case Event(CallFailed, CircuitBreakerStateData(_, true)) ⇒
        log.debug("HALF-OPEN: First forwarded call failed returning to OPEN state")
        goto(Open)

      case Event(CallFailed, CircuitBreakerStateData(_, false)) ⇒
        log.debug("HALF-OPEN: Call received a further call failed notification, probably from a previous timed out event, ignoring")
        stay

      case Event(message, state @ CircuitBreakerStateData(_, false)) ⇒
        log.debug("HALF-OPEN: First message {} received, forwarding it to target {}", message, target)
        forwardRequest(message, sender, state, log)
        stay using state.copy(firstHalfOpenMessageSent = true)

      case Event(message, CircuitBreakerStateData(_, true)) ⇒
        val failureNotification = failureMap(CircuitOpenFailure(message))
        log.debug("HALF-OPEN: Failing request for message {}, sending failure notification {} to sender {}", message, failureNotification, sender)
        sender ! failureNotification
        stay
    }
  }

  def forwardRequest(message: Any, currentSender: ActorRef, state: CircuitBreakerStateData, log: LoggingAdapter) = {
    import context.dispatcher

    target.ask(message)(callTimeout).onComplete {
      case Success(response) ⇒
        log.debug("Request '{}' has been replied to with response {}, forwarding to original sender {}", message, currentSender)

        currentSender ! response

        val isFailure = failureDetector(response)

        if (isFailure) {
          log.debug("Response '{}' is considered as failure sending self-message to ask incrementing failure count (origin state was {})",
            response, state)

          self ! CallFailed
        } else {

          log.debug("Request '{}' succeeded with response {}, returning response to sender {} and sending message to ask to reset failure count (origin state was {})",
            message, response, currentSender, state)

          self ! CallSucceeded
        }

      case Failure(reason) ⇒
        log.debug("Request '{}' to target {} failed with exception {}, sending self-message to ask incrementing failure count (origin state was {})",
          message, target, reason, state)

        self ! CallFailed
    }
  }

  onTransition {
    case from -> Closed ⇒
      log.debug("Moving from state {} to state CLOSED", from)
      circuitEventListener foreach { _ ! CircuitClosed(self) }

    case from -> HalfOpen ⇒
      log.debug("Moving from state {} to state HALF OPEN", from)
      circuitEventListener foreach { _ ! CircuitHalfOpen(self) }

    case from -> Open ⇒
      log.debug("Moving from state {} to state OPEN", from)
      circuitEventListener foreach { _ ! CircuitOpen(self) }
  }

}
