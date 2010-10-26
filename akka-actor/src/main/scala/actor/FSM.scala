/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import scala.collection.mutable
import java.util.concurrent.{ScheduledFuture, TimeUnit}

trait FSM[S, D] {
  this: Actor =>

  type StateFunction = scala.PartialFunction[Event, State]

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

  protected final def setInitialState(stateName: S, stateData: D, timeout: Option[Long] = None) = {
    setState(State(stateName, stateData, timeout))
  }

  protected final def inState(stateName: S)(stateFunction: StateFunction) = {
    register(stateName, stateFunction)
  }

  protected final def goto(nextStateName: S): State = {
    State(nextStateName, currentState.stateData)
  }

  protected final def stay(): State = {
    goto(currentState.stateName)
  }

  protected final def reply(replyValue: Any): State = {
    self.sender.foreach(_ ! replyValue)
    stay()
  }

  /**
   * Stop
   */
  protected final def stop(): State = {
    stop(Normal)
  }

  protected final def stop(reason: Reason): State = {
    stop(reason, currentState.stateData)
  }

  protected final def stop(reason: Reason, stateData: D): State = {
    log.info("Stopped because of reason: %s", reason)
    terminate(reason, currentState.stateName, stateData)
    self.stop
    State(currentState.stateName, stateData)
  }

  def terminate(reason: Reason, stateName: S, stateData: D) = ()

  def whenUnhandled(stateFunction: StateFunction) = {
    handleEvent = stateFunction
  }

  private var handleEvent: StateFunction = {
    case Event(value, stateData) =>
      log.warning("Event %s not handled in state %s - keeping current state with data %s", value, currentState.stateName, stateData)
      currentState
  }

  override final protected def receive: Receive = {
    case StateTimeout if (self.dispatcher.mailboxSize(self) > 0) => ()
    // state timeout when new message in queue, skip this timeout
    case value => {
      timeoutFuture = timeoutFuture.flatMap {ref => ref.cancel(true); None}
      val event = Event(value, currentState.stateData)
      val nextState = (transitions(currentState.stateName) orElse handleEvent).apply(event)
      if (self.isRunning) {
        setState(nextState)
      }
    }
  }

  private def setState(nextState: State) = {
    if (!transitions.contains(nextState.stateName)) {
      stop(Failure("Next state %s not available".format(nextState.stateName)))
    } else {
      currentState = nextState
      currentState.timeout.foreach {t => timeoutFuture = Some(Scheduler.scheduleOnce(self, StateTimeout, t, TimeUnit.MILLISECONDS))}
    }
  }

  case class Event(event: Any, stateData: D)

  case class State(stateName: S, stateData: D, timeout: Option[Long] = None) {
    def until(timeout: Long): State = {
      copy(timeout = Some(timeout))
    }

    def then(nextStateName: S): State = {
      copy(stateName = nextStateName)
    }

    def replying(replyValue:Any): State = {
      self.sender.foreach(_ ! replyValue)
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
}
