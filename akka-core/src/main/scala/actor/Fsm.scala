package actor

import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{ActorRef, Scheduler, Actor}
import se.scalablesolutions.akka.stm.Ref
import se.scalablesolutions.akka.stm.local._

trait Fsm[S] { self: Actor =>
  
  type StateFunction = scala.PartialFunction[Event, State]

  val stateRef: Ref[State] = Ref(initialState)
  @volatile var timeoutActor: Option[ActorRef] = None

  def initialState: State

  def handleEvent: StateFunction = {
    case event@Event(value,stateData) =>
      log.warning("No state for event with value %s - keeping current state %s", value, stateData)
      val currentState: State = stateRef.getOrElse(initialState)
      State(NextState, currentState.stateFunction, stateData, currentState.timeout)
  }


  override protected def receive: Receive = {
    case value => {
      atomic {
        timeoutActor = timeoutActor.flatMap{ref => Scheduler.unschedule(ref); None}

        val currentState: State = stateRef.getOrElse(initialState)
        val event = Event(value, currentState.stateData)
        val newState = (currentState.stateFunction orElse handleEvent).apply(event)

        newState match {
          case State(Reply, _, _, _, Some(replyValue)) => this.self.reply(replyValue)
          case _ => () // ignore for now
        }

        newState.timeout.foreach{timeout =>
          timeoutActor = Some(Scheduler.scheduleOnce(this.self, StateTimeout, timeout, TimeUnit.MILLISECONDS))
        }

        stateRef.swap(newState)
      }
    }
  }

  
  case class State(stateEvent: StateEvent,
                   stateFunction: StateFunction,
                   stateData: S,
                   timeout: Option[Int] = None,
                   replyValue: Option[Any] =None)

  case class Event(event: Any, stateData: S)

  sealed trait StateEvent
  object NextState extends StateEvent
  object Reply extends StateEvent

  object StateTimeout
}
