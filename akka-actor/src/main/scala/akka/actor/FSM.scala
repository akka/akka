/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import scala.collection.mutable
import java.util.concurrent.{ScheduledFuture, TimeUnit}

object FSM {
  sealed trait Reason
  case object Normal extends Reason
  case object Shutdown extends Reason
  case class Failure(cause: Any) extends Reason

  case object StateTimeout
  case class TimeoutMarker(generation: Long)
}

trait FSM[S, D] {
  this: Actor =>
  import FSM._

  type StateFunction = scala.PartialFunction[Event, State]

  /** DSL */
  protected final def notifying(transitionHandler: PartialFunction[Transition, Unit]) = {
    transitionEvent = transitionHandler
  }

  protected final def when(stateName: S)(stateFunction: StateFunction) = {
    register(stateName, stateFunction)
  }

  protected final def startWith(stateName: S,
                                stateData: D,
                                timeout: Option[(Long, TimeUnit)] = None) = {
    applyState(State(stateName, stateData, timeout))
  }

  protected final def goto(nextStateName: S): State = {
    State(nextStateName, currentState.stateData)
  }

  protected final def stay(): State = {
    goto(currentState.stateName)
  }

  protected final def stop(): State = {
    stop(Normal)
  }

  protected final def stop(reason: Reason): State = {
    stop(reason, currentState.stateData)
  }

  protected final def stop(reason: Reason, stateData: D): State = {
    stay using stateData withStopReason(reason)
  }

  def whenUnhandled(stateFunction: StateFunction) = {
    handleEvent = stateFunction
  }

  def onTermination(terminationHandler: PartialFunction[Reason, Unit]) = {
    terminateEvent = terminationHandler
  }

  /** FSM State data and default handlers */
  private var currentState: State = _
  private var timeoutFuture: Option[ScheduledFuture[AnyRef]] = None
  private var generation: Long = 0L


  private val stateFunctions = mutable.Map[S, StateFunction]()
  private def register(name: S, function: StateFunction) {
    if (stateFunctions contains name) {
      stateFunctions(name) = stateFunctions(name) orElse function
    } else {
      stateFunctions(name) = function
    }
  }

  private var handleEvent: StateFunction = {
    case Event(value, stateData) =>
      log.slf4j.warn("Event {} not handled in state {}, staying at current state", value, currentState.stateName)
      stay
  }

  private var terminateEvent: PartialFunction[Reason, Unit] = {
    case failure@Failure(_) => log.slf4j.error("Stopping because of a {}", failure)
    case reason => log.slf4j.info("Stopping because of reason: {}", reason)
  }

  private var transitionEvent: PartialFunction[Transition, Unit] = {
    case Transition(from, to) => log.slf4j.debug("Transitioning from state {} to {}", from, to)
  }

  override final protected def receive: Receive = {
    case TimeoutMarker(gen) =>
      if (generation == gen) {
        processEvent(StateTimeout)
      }
    case value => {
      timeoutFuture = timeoutFuture.flatMap {ref => ref.cancel(true); None}
      generation += 1
      processEvent(value)
    }
  }

  private def processEvent(value: Any) = {
    val event = Event(value, currentState.stateData)
    val nextState = (stateFunctions(currentState.stateName) orElse handleEvent).apply(event)
    nextState.stopReason match {
      case Some(reason) => terminate(reason)
      case None => makeTransition(nextState)
    }
  }

  private def makeTransition(nextState: State) = {
    if (!stateFunctions.contains(nextState.stateName)) {
      terminate(Failure("Next state %s does not exist".format(nextState.stateName)))
    } else {
      if (currentState.stateName != nextState.stateName) {
        transitionEvent.apply(Transition(currentState.stateName, nextState.stateName))
      }
      applyState(nextState)
    }
  }

  private def applyState(nextState: State) = {
    currentState = nextState
    currentState.timeout.foreach { t =>
      timeoutFuture = Some(Scheduler.scheduleOnce(self, TimeoutMarker(generation), t._1, t._2))
    }
  }

  private def terminate(reason: Reason) = {
    terminateEvent.apply(reason)
    self.stop
  }


  case class Event(event: Any, stateData: D)

  case class State(stateName: S, stateData: D, timeout: Option[(Long, TimeUnit)] = None) {

    def forMax(timeout: (Long, TimeUnit)): State = {
      copy(timeout = Some(timeout))
    }

    def replying(replyValue:Any): State = {
      self.sender match {
        case Some(sender) => sender ! replyValue
        case None => log.slf4j.error("Unable to send reply value {}, no sender reference to reply to", replyValue)
      }
      this
    }

    def using(nextStateDate: D): State = {
      copy(stateData = nextStateDate)
    }

    private[akka] var stopReason: Option[Reason] = None
    private[akka] def withStopReason(reason: Reason): State = {
      stopReason = Some(reason)
      this
    }
  }

  case class Transition(from: S, to: S)
}
