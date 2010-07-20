package actor

import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{ActorRef, Scheduler, Actor}

trait Fsm[S] { self: Actor =>
  
  type StateFunction = scala.PartialFunction[Event, State]

  @volatile var currentState: State = initialState
  @volatile var timeoutActor: Option[ActorRef] = None

  def initialState: State

  def handleEvent: StateFunction = {
    case event@Event(value,stateData) =>
      log.warning("No state for event with value %s - keeping current state %s", value, stateData)
      State(NextState, currentState.stateFunction, stateData, currentState.timeout)
  }


  override protected def receive: Receive = {
    case value => {
      timeoutActor = timeoutActor flatMap { ref => Scheduler.unschedule(ref); None }

      val event = Event(value, currentState.stateData)
      currentState = (currentState.stateFunction orElse handleEvent).apply(event)

      currentState.timeout.foreach{timeout =>
        timeoutActor = Some(Scheduler.scheduleOnce(this.self, StateTimeout, timeout, TimeUnit.MILLISECONDS))
      }

      currentState match {
        case State(Reply, _, _, _, reply) => reply.foreach(this.self.reply)
      }
    }
  }

  
  case class State(stateEvent: StateEvent,
                   stateFunction: StateFunction,
                   stateData: S,
                   timeout: Option[Int] = None,
                   reply: Option[Any] =None)

  case class Event(event: Any, stateData: S)

  sealed trait StateEvent
  object NextState extends StateEvent
  object Reply extends StateEvent

  object StateTimeout
}
