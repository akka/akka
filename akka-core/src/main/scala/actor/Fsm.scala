package actor

import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{ActorRef, Scheduler, Actor}

trait Fsm[S] { self: Actor =>
  
  type State = scala.PartialFunction[Event, NextState]

  @volatile var currentState: NextState = initialState
  @volatile var timeoutActor: Option[ActorRef] = None

  def initialState: NextState

  def handleEvent: State = {
    case event@Event(value,stateData) =>
      log.warning("No state for event with value %s - keeping current state %s", value, stateData)
      NextState(currentState.state, stateData, currentState.timeout)
  }


  override protected def receive: Receive = {
    case value => {
      timeoutActor = timeoutActor flatMap { ref => Scheduler.unschedule(ref); None }

      val event = Event(value, currentState.stateData)
      currentState = (currentState.state orElse handleEvent).apply(event)

      currentState.timeout.foreach{timeout =>
        timeoutActor = Some(Scheduler.scheduleOnce(this.self, StateTimeout, timeout, TimeUnit.MILLISECONDS))
      }
    }
  }

  case class NextState(state: State, stateData: S, timeout: Option[Int] = None)
  case class Event(event: Any, stateData: S)
  object StateTimeout
}
