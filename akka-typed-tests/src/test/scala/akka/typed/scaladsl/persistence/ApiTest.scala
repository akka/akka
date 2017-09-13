package akka.typed.scaladsl.persistence

import scala.concurrent.duration._
import akka.typed.{ ActorRef, Behavior }
import akka.typed.scaladsl.{ ActorContext, TimerScheduler }

import scala.concurrent.ExecutionContext

class ApiTest {
  object TypedPersistentActor {

    sealed trait PersistentEffect[+Event]

    case object PersistNothing extends PersistentEffect[Nothing]

    case class PersistAnd[Event](event: Event, callback: Event ⇒ Unit) extends PersistentEffect[Event]

    case class Persist[Event](event: Event) extends PersistentEffect[Event] {
      def andThen(callback: Event ⇒ Unit) = PersistAnd(event, callback)
    }

  }

  object Actor {
    import TypedPersistentActor._

    def persistent[Command, Event, State](
      persistenceId:      String,
      initialState:       State,
      commandHandler:     State ⇒ ((ActorContext[Command], Command) ⇒ PersistentEffect[Event]),
      onEvent:            (State, Event) ⇒ State,
      onRecoveryComplete: (ActorContext[Command], State) ⇒ Unit                                = (_: ActorContext[Command], _: State) ⇒ ()
    ) = ???
  }

  import TypedPersistentActor._

  object Simple {
    sealed trait MyCommand
    case class Cmd(data: String) extends MyCommand

    sealed trait MyEvent
    case class Evt(data: String) extends MyEvent

    case class ExampleState(events: List[String] = Nil)

    Actor.persistent[MyCommand, MyEvent, ExampleState](
      persistenceId = "sample-id-1",

      initialState = ExampleState(Nil),

      commandHandler = _ ⇒ (ctx, cmd) ⇒ {
        cmd match {
          case Cmd(data) ⇒ Persist(Evt(data))
        }
      },

      onEvent = (state, evt) ⇒ evt match {
        case Evt(data) ⇒ state.copy(data :: state.events)
      }
    )
  }

  object WithAck {
    case object Ack

    sealed trait MyCommand
    case class Cmd(data: String, sender: ActorRef[Ack.type]) extends MyCommand

    sealed trait MyEvent
    case class Evt(data: String) extends MyEvent

    case class ExampleState(events: List[String] = Nil)

    Actor.persistent[MyCommand, MyEvent, ExampleState](
      persistenceId = "sample-id-1",

      initialState = ExampleState(Nil),

      commandHandler = _ ⇒ (ctx, cmd) ⇒ {
        cmd match {
          case Cmd(data, sender) ⇒
            Persist(Evt(data))
              .andThen { evt ⇒ { sender ! Ack } }
        }
      },

      onEvent = (state, evt) ⇒ evt match {
        case Evt(data) ⇒ state.copy(data :: state.events)
      }
    )
  }

  object RecoveryComplete {
    import akka.typed.scaladsl.AskPattern._

    sealed trait Command
    case class DoSideEffect(data: String) extends Command
    case class AcknowledgeSideEffect(correlationId: Int) extends Command

    sealed trait Event
    case class IntentRecorded(correlationId: Int, data: String) extends Event
    case class SideEffectAcknowledged(correlationId: Int) extends Event

    case class EventsInFlight(nextCorrelationId: Int, dataByCorrelationId: Map[Int, String])

    case class Request(correlationId: Int, data: String, sender: ActorRef[Response])
    case class Response(correlationId: Int)
    val sideEffectProcessor: ActorRef[Request] = ???
    implicit val timeout: akka.util.Timeout = 1.second
    implicit val scheduler: akka.actor.Scheduler = ???
    implicit val ec: ExecutionContext = ???

    def performSideEffect(sender: ActorRef[AcknowledgeSideEffect], correlationId: Int, data: String) = {
      (sideEffectProcessor ? (Request(correlationId, data, _: ActorRef[Response])))
        .map(response ⇒ AcknowledgeSideEffect(response.correlationId))
        .foreach(sender ! _)
    }

    Actor.persistent[Command, Event, EventsInFlight](
      persistenceId = "recovery-complete-id",

      initialState = EventsInFlight(0, Map.empty),

      commandHandler = state ⇒ (ctx, cmd) ⇒ cmd match {
        case DoSideEffect(data) ⇒
          Persist(IntentRecorded(state.nextCorrelationId, data)).andThen { evt ⇒
            performSideEffect(ctx.self, evt.correlationId, data)
          }
        case AcknowledgeSideEffect(correlationId) ⇒
          Persist(SideEffectAcknowledged(correlationId))
      },

      onEvent = (state, evt) ⇒ evt match {
        case IntentRecorded(correlationId, data) ⇒
          EventsInFlight(
            nextCorrelationId = correlationId + 1,
            dataByCorrelationId = state.dataByCorrelationId + (correlationId → data))
        case SideEffectAcknowledged(correlationId) ⇒
          state.copy(dataByCorrelationId = state.dataByCorrelationId - correlationId)
      },

      onRecoveryComplete = (ctx, state) ⇒ {
        state.dataByCorrelationId.foreach {
          case (correlationId, data) ⇒ performSideEffect(ctx.self, correlationId, data)
        }
      }

    )

  }

  // Example with 'become'
  object Become {
    sealed trait Mood
    case object Happy extends Mood
    case object Sad extends Mood

    sealed trait Command
    case class Greet(name: String) extends Command
    case object MoodSwing extends Command

    sealed trait Event
    case class MoodChanged(to: Mood) extends Event

    val b: Behavior[Command] = Actor.persistent[Command, Event, Mood](
      persistenceId = "myPersistenceId",
      initialState = Happy,
      commandHandler = {
      case Happy ⇒ (_, cmd) ⇒ cmd match {
        case Greet(whom) ⇒
          println(s"Super happy to meet you $whom!")
          PersistNothing
        case MoodSwing ⇒ Persist(MoodChanged(Sad))
      }
      case Sad ⇒ (_, cmd) ⇒ cmd match {
        case Greet(whom) ⇒
          println(s"hi $whom")
          PersistNothing
        case MoodSwing ⇒ Persist(MoodChanged(Happy))
      }
    },
      onEvent = {
      case (_, MoodChanged(to)) ⇒ to
    }
    )

    akka.typed.scaladsl.Actor.withTimers((timers: TimerScheduler[Command]) ⇒ {
      timers.startPeriodicTimer("swing", MoodSwing, 10.seconds)
      b
    })
  }

  // Something with spawning child actors
  // explicit snapshots
}