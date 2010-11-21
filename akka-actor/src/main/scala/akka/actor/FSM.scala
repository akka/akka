/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import scala.collection.mutable
import java.util.concurrent.{ScheduledFuture, TimeUnit}

trait FSM[S, D] {
  this: Actor =>

  type StateFunction = scala.PartialFunction[Event, State]

  /** DSL */
  protected final def notifying(transitionHandler: PartialFunction[Transition, Unit]) = {
    transitionEvent = transitionHandler
  }

  protected final def when(stateName: S)(stateFunction: StateFunction) = {
    register(stateName, stateFunction)
  }

  protected final def startWith(stateName: S, stateData: D, timeout: Option[Long] = None) = {
    setState(State(stateName, stateData, timeout))
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
    self ! Stop(reason, stateData)
    stay
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

  private val transitions = mutable.Map[S, StateFunction]()
  private def register(name: S, function: StateFunction) {
    if (transitions contains name) {
      transitions(name) = transitions(name) orElse function
    } else {
      transitions(name) = function
    }
  }

  private var handleEvent: StateFunction = {
    case Event(value, stateData) =>
      log.warning("Event %s not handled in state %s, staying at current state", value, currentState.stateName)
      stay
  }

  private var terminateEvent: PartialFunction[Reason, Unit] = {
    case failure@Failure(_) => log.error("Stopping because of a %s", failure)
    case reason => log.info("Stopping because of reason: %s", reason)
  }

  private var transitionEvent: PartialFunction[Transition, Unit] = {
    case Transition(from, to) => log.debug("Transitioning from state %s to %s", from, to)
  }

  override final protected def receive: Receive = {
    case Stop(reason, stateData) =>
      terminateEvent.apply(reason)
      self.stop
    case StateTimeout if (self.dispatcher.mailboxSize(self) > 0) =>
    log.trace("Ignoring StateTimeout - ")
    // state timeout when new message in queue, skip this timeout
    case value => {
      timeoutFuture = timeoutFuture.flatMap {ref => ref.cancel(true); None}
      val event = Event(value, currentState.stateData)
      val nextState = (transitions(currentState.stateName) orElse handleEvent).apply(event)
      setState(nextState)
    }
  }

  private def setState(nextState: State) = {
    if (!transitions.contains(nextState.stateName)) {
      stop(Failure("Next state %s does not exist".format(nextState.stateName)))
    } else {
      if (currentState != null && currentState.stateName != nextState.stateName) {
        transitionEvent.apply(Transition(currentState.stateName, nextState.stateName))
      }
      currentState = nextState
      currentState.timeout.foreach {
        t =>
          timeoutFuture = Some(Scheduler.scheduleOnce(self, StateTimeout, t, TimeUnit.MILLISECONDS))
      }
    }
  }

  case class Event(event: Any, stateData: D)

  case class State(stateName: S, stateData: D, timeout: Option[Long] = None) {

    def until(timeout: Long): State = {
      copy(timeout = Some(timeout))
    }

    def replying(replyValue:Any): State = {
      self.sender match {
        case Some(sender) => sender ! replyValue
        case None => log.error("Unable to send reply value %s, no sender reference to reply to", replyValue)
      }
      this
    }

    def using(nextStateDate: D): State = {
      copy(stateData = nextStateDate)
    }
  }

  sealed trait Reason
  case object Normal extends Reason
  case object Shutdown extends Reason
  case class Failure(cause: Any) extends Reason

  case object StateTimeout

  case class Transition(from: S, to: S)

  private case class Stop(reason: Reason, stateData: D)
}
