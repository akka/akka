package akka.typed.scaladsl.persistence

import akka.typed

import scala.concurrent.duration._
import akka.typed.{ ActorRef, Behavior, ExtensibleBehavior, Signal }
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

    class PersistentBehavior[Command, Event, State] extends ExtensibleBehavior[Command] {
      override def receiveSignal(ctx: typed.ActorContext[Command], msg: Signal): Behavior[Command] = ???
      override def receiveMessage(ctx: typed.ActorContext[Command], msg: Command): Behavior[Command] = ???

      def onRecoveryComplete(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State] = ???
      def snapshot[Snapshot](
        onState: State ⇒ Option[Snapshot]          = (_: State) ⇒ None,
        onEvent: Event ⇒ Option[Snapshot]          = (_: Event) ⇒ None,
        on:      (State, Event) ⇒ Option[Snapshot] = (_: State, _: Event) ⇒ None,
        recover: Snapshot ⇒ Option[State]
      ): PersistentBehavior[Command, Event, State] = ???
    }

    def persistent[Command, Event, State](
      persistenceId:  String,
      initialState:   State,
      commandHandler: State ⇒ ((ActorContext[Command], Command) ⇒ PersistentEffect[Event]),
      onEvent:        (State, Event) ⇒ State
    ): PersistentBehavior[Command, Event, State] = ???
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
      }).onRecoveryComplete {
        case (ctx, state) ⇒ {
          state.dataByCorrelationId.foreach {
            case (correlationId, data) ⇒ performSideEffect(ctx.self, correlationId, data)
          }
        }
      }

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

  // explicit snapshots
  object ExplicitSnapshots {
    type Task = String

    sealed trait Command
    case class RegisterTask(task: Task) extends Command
    case class TaskDone(task: Task) extends Command

    sealed trait Event
    case class TaskRegistered(task: Task) extends Event
    case class TaskRemoved(task: Task) extends Event

    case class State(tasksInFlight: List[Task])

    Actor.persistent[Command, Event, State](
      persistenceId = "asdf",
      initialState = State(Nil),
      commandHandler = state ⇒ {
        case (_, RegisterTask(task)) ⇒ Persist(TaskRegistered(task))
        case (_, TaskDone(task))     ⇒ Persist(TaskRemoved(task))
      },
      onEvent = (state, evt) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      }
    ).snapshot[Unit](
        state ⇒ if (state.tasksInFlight.isEmpty) Some(()) else None,
        recover = _ ⇒ Some(State(Nil))
      )
  }

  object SpawnChild {
    type Task = String
    sealed trait Command
    case class RegisterTask(task: Task) extends Command
    case class TaskDone(task: Task) extends Command

    sealed trait Event
    case class TaskRegistered(task: Task) extends Event
    case class TaskRemoved(task: Task) extends Event

    case class State(tasksInFlight: List[Task])

    def worker(task: Task): Behavior[Nothing] = ???

    Actor.persistent[Command, Event, State](
      persistenceId = "asdf",
      initialState = State(Nil),
      commandHandler = _ ⇒ {
        case (ctx, RegisterTask(task)) ⇒ Persist(TaskRegistered(task))
          .andThen { _ ⇒
            val child = ctx.spawn[Nothing](worker(task), task)
            // This assumes *any* termination of the child may trigger a `TaskDone`:
            ctx.watchWith(child, TaskDone(task))
          }
        case (_, TaskDone(task)) ⇒ Persist(TaskRemoved(task))
      },
      onEvent = (state, evt) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      }
    )
  }
}