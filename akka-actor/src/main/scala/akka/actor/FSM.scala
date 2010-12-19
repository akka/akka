/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.util._

import scala.collection.mutable
import java.util.concurrent.{ScheduledFuture, TimeUnit}

object FSM {
  sealed trait Reason
  case object Normal extends Reason
  case object Shutdown extends Reason
  case class Failure(cause: Any) extends Reason

  case object StateTimeout
  case class TimeoutMarker(generation: Long)
  case class Timer(name : String, msg : AnyRef, repeat : Boolean) {
	  private var ref : Option[ScheduledFuture[AnyRef]] = _
	  def schedule(actor : ActorRef, timeout : Duration) {
	 	  if (repeat) {
	 	 	  ref = Some(Scheduler.schedule(actor, this, timeout.length, timeout.length, timeout.unit))
	 	  } else {
	 		  ref = Some(Scheduler.scheduleOnce(actor, this, timeout.length, timeout.unit))
	 	  }
	  }
	  def cancel {
	 	  ref = ref flatMap {t => t.cancel(true); None}
	  }
  }
  
  /*
   * With these implicits in scope, you can write "5 seconds" anywhere a
   * Duration or Option[Duration] is expected. This is conveniently true
   * for derived classes.
   */
  implicit def d2od(d : Duration) : Option[Duration] = Some(d)
  implicit def p2od(p : (Long, TimeUnit)) : Duration = new Duration(p._1, p._2)
  implicit def i2d(i : Int) : DurationInt = new DurationInt(i)
  implicit def l2d(l : Long) : DurationLong = new DurationLong(l)
}

trait FSM[S, D] {
  this: Actor =>
  import FSM._

  type StateFunction = scala.PartialFunction[Event, State]
  type Timeout = Option[Duration]

  /** DSL */
  protected final def notifying(transitionHandler: PartialFunction[Transition, Unit]) = {
    transitionEvent = transitionHandler
  }

  protected final def when(stateName: S, stateTimeout: Timeout = None)(stateFunction: StateFunction) = {
    register(stateName, stateFunction, stateTimeout)
  }

  protected final def startWith(stateName: S,
                                stateData: D,
                                timeout: Timeout = None) = {
    currentState = State(stateName, stateData, timeout)
  }

  protected final def goto(nextStateName: S): State = {
    State(nextStateName, currentState.stateData)
  }

  protected final def stay(): State = {
    // cannot directly use currentState because of the timeout field
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

  /**
   * Schedule named timer to deliver message after given delay, possibly repeating.
   * @param name identifier to be used with cancelTimer()
   * @param msg message to be delivered
   * @param timeout delay of first message delivery and between subsequent messages
   * @param repeat send once if false, scheduleAtFixedRate if true
   * @return current State
   */
  protected final def setTimer(name : String, msg : AnyRef, timeout : Duration, repeat : Boolean):State = {
    if (timers contains name) {
    	timers(name).cancel
    }
	val timer = Timer(name, msg, repeat)
    timer.schedule(self, timeout)
    timers(name) = timer
    stay
  }
  
  /**
   * Cancel named timer, ensuring that the message is not subsequently delivered (no race).
   * @param name
   * @return
   */
  protected final def cancelTimer(name : String) = {
	  if (timers contains name) {
	 	  timers(name).cancel
	 	  timers -= name
	  }
  }
  
  protected final def timerActive_?(name : String) = timers contains name

  def whenUnhandled(stateFunction: StateFunction) = {
    handleEvent = stateFunction
  }

  def onTermination(terminationHandler: PartialFunction[Reason, Unit]) = {
    terminateEvent = terminationHandler
  }
  
  def initialize {
	  // check existence of initial state and setup timeout
	  makeTransition(currentState)
  }

  /** FSM State data and default handlers */
  private var currentState: State = _
  private var timeoutFuture: Option[ScheduledFuture[AnyRef]] = None
  private var generation: Long = 0L

  private val timers = mutable.Map[String, Timer]()

  private val stateFunctions = mutable.Map[S, StateFunction]()
  private val stateTimeouts = mutable.Map[S, Timeout]()
  
  private def register(name: S, function: StateFunction, timeout: Timeout) {
    if (stateFunctions contains name) {
      stateFunctions(name) = stateFunctions(name) orElse function
      stateTimeouts(name) = timeout orElse stateTimeouts(name)
    } else {
      stateFunctions(name) = function
      stateTimeouts(name) = timeout
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
    case t @ Timer(name, msg, repeat) =>
      if (timerActive_?(name)) {
   	    processEvent(msg)
   	    if (!repeat) {
   	      timers -= name
   	    }
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
      currentState = nextState
      currentState.timeout orElse stateTimeouts(currentState.stateName) foreach { t =>
      	if (t.length >= 0) {
      		timeoutFuture = Some(Scheduler.scheduleOnce(self, TimeoutMarker(generation), t.length, t.unit))
      	}
      }
    }
  }

  private def terminate(reason: Reason) = {
    terminateEvent.apply(reason)
    self.stop
  }


  case class Event(event: Any, stateData: D)

  case class State(stateName: S, stateData: D, timeout: Timeout = None) {

    def forMax(timeout: Duration): State = {
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
