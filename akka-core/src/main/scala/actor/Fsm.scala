package actor

import se.scalablesolutions.akka.actor.{Scheduler, Actor}
import se.scalablesolutions.akka.stm.Ref
import se.scalablesolutions.akka.stm.local._
import java.util.concurrent.{ScheduledFuture, TimeUnit}

trait Fsm[S] {
  this: Actor =>

  type StateFunction = scala.PartialFunction[Event, State]

  var currentState: State = initialState
  var timeoutFuture: Option[ScheduledFuture[AnyRef]] = None

  def initialState: State

  def handleEvent: StateFunction = {
    case event@Event(value, stateData) =>
      log.warning("No state for event with value %s - keeping current state %s at %s", value, stateData, self.id)
      State(NextState, currentState.stateFunction, stateData, currentState.timeout)
  }


  override final protected def receive: Receive = {
    case value => {
      timeoutFuture = timeoutFuture.flatMap {ref => ref.cancel(true); None}

      val event = Event(value, currentState.stateData)
      val newState = (currentState.stateFunction orElse handleEvent).apply(event)

      currentState = newState

      newState match {
        case State(Reply, _, _, _, Some(replyValue)) => self.sender.foreach(_ ! replyValue)
        case _ => () // ignore for now
      }

      newState.timeout.foreach {
        timeout =>
          timeoutFuture = Some(Scheduler.scheduleOnce(self, StateTimeout, timeout, TimeUnit.MILLISECONDS))
      }
    }
  }

  case class State(stateEvent: StateEvent,
                   stateFunction: StateFunction,
                   stateData: S,
                   timeout: Option[Int] = None,
                   replyValue: Option[Any] = None)

  case class Event(event: Any, stateData: S)

  sealed trait StateEvent
  object NextState extends StateEvent
  object Reply extends StateEvent

  object StateTimeout
}
