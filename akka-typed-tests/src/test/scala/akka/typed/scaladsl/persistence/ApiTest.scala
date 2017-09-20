package akka.typed.scaladsl.persistence

import akka.typed

import scala.concurrent.duration._
import akka.typed.{ ActorRef, Behavior, ExtensibleBehavior, Signal, Terminated }
import akka.typed.scaladsl.{ ActorContext, TimerScheduler }

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class ApiTest {
  object TypedPersistentActor {

    sealed abstract class PersistentEffect[+Event, State]() {
      def andThen(callback: State ⇒ Unit): PersistentEffect[Event, State]
    }

    case class PersistNothing[Event, State](callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
      def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
    }

    case class Persist[Event, State](event: Event, callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
      def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
    }

    case class Unhandled[Event, State](callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
      def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
    }

    class ActionHandler[Command: ClassTag, Event, State](val handler: ((Any, State, ActorContext[Command]) ⇒ PersistentEffect[Event, State])) {
      def onSignal(signalHandler: PartialFunction[(Any, State, ActorContext[Command]), PersistentEffect[Event, State]]): ActionHandler[Command, Event, State] =
        ActionHandler {
          case (command: Command, state, ctx) ⇒ handler(command, state, ctx)
          case (signal: Signal, state, ctx)   ⇒ signalHandler.orElse(unhandledSignal).apply((signal, state, ctx))
          case _                              ⇒ Unhandled()
        }
      private val unhandledSignal: PartialFunction[(Any, State, ActorContext[Command]), PersistentEffect[Event, State]] = { case _ ⇒ Unhandled() }
    }
    object ActionHandler {
      def cmd[Command: ClassTag, Event, State](commandHandler: Command ⇒ PersistentEffect[Event, State]): ActionHandler[Command, Event, State] = ???
      def apply[Command: ClassTag, Event, State](commandHandler: ((Command, State, ActorContext[Command]) ⇒ PersistentEffect[Event, State])): ActionHandler[Command, Event, State] = ???
      def byState[Command: ClassTag, Event, State](actionHandler: State ⇒ ActionHandler[Command, Event, State]): ActionHandler[Command, Event, State] =
        new ActionHandler(handler = {
          case (action, state, ctx) ⇒ actionHandler(state).handler(action, state, ctx)
        })
    }
  }

  object Actor {
    import TypedPersistentActor._

    class PersistentBehavior[Command, Event, State] extends ExtensibleBehavior[Command] {
      override def receiveSignal(ctx: typed.ActorContext[Command], msg: Signal): Behavior[Command] = ???
      override def receiveMessage(ctx: typed.ActorContext[Command], msg: Command): Behavior[Command] = ???

      def onRecoveryComplete(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State] = ???
      def snapshotOnState(predicate: State ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???
      def snapshotOn(predicate: (State, Event) ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???
    }

    def persistent[Command, Event, State](
      persistenceId:  String,
      initialState:   State,
      commandHandler: ActionHandler[Command, Event, State],
      onEvent:        (Event, State) ⇒ State
    ): PersistentBehavior[Command, Event, State] = ???
  }

  import TypedPersistentActor._

  import akka.typed.scaladsl.AskPattern._
  implicit val timeout: akka.util.Timeout = 1.second
  implicit val scheduler: akka.actor.Scheduler = ???
  implicit val ec: ExecutionContext = ???

  object Simple {
    sealed trait MyCommand
    case class Cmd(data: String) extends MyCommand

    sealed trait MyEvent
    case class Evt(data: String) extends MyEvent

    case class ExampleState(events: List[String] = Nil)

    Actor.persistent[MyCommand, MyEvent, ExampleState](
      persistenceId = "sample-id-1",

      initialState = ExampleState(Nil),

      commandHandler = ActionHandler.cmd {
        case Cmd(data) ⇒ Persist(Evt(data))
      },

      onEvent = {
        case (Evt(data), state) ⇒ state.copy(data :: state.events)
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

      commandHandler = ActionHandler.cmd {
        case Cmd(data, sender) ⇒
          Persist[MyEvent, ExampleState](Evt(data))
            .andThen { _ ⇒ { sender ! Ack } }
      },

      onEvent = {
        case (Evt(data), state) ⇒ state.copy(data :: state.events)
      }
    )
  }

  object RecoveryComplete {
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

    def performSideEffect(sender: ActorRef[AcknowledgeSideEffect], correlationId: Int, data: String) = {
      (sideEffectProcessor ? (Request(correlationId, data, _: ActorRef[Response])))
        .map(response ⇒ AcknowledgeSideEffect(response.correlationId))
        .foreach(sender ! _)
    }

    Actor.persistent[Command, Event, EventsInFlight](
      persistenceId = "recovery-complete-id",

      initialState = EventsInFlight(0, Map.empty),

      commandHandler = ActionHandler((cmd, state, ctx) ⇒ cmd match {
        case DoSideEffect(data) ⇒
          Persist[Event, EventsInFlight](IntentRecorded(state.nextCorrelationId, data)).andThen { _ ⇒
            performSideEffect(ctx.self, state.nextCorrelationId, data)
          }
        case AcknowledgeSideEffect(correlationId) ⇒
          Persist(SideEffectAcknowledged(correlationId))
      }),

      onEvent = (evt, state) ⇒ evt match {
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
      commandHandler = ActionHandler.byState {
        case Happy ⇒ ActionHandler.cmd {
          case Greet(whom) ⇒
            println(s"Super happy to meet you $whom!")
            PersistNothing()
          case MoodSwing ⇒ Persist(MoodChanged(Sad))
        }
        case Sad ⇒ ActionHandler.cmd {
          case Greet(whom) ⇒
            println(s"hi $whom")
            PersistNothing()
          case MoodSwing ⇒ Persist(MoodChanged(Happy))
        }
      },
      onEvent = {
        case (MoodChanged(to), _) ⇒ to
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
      commandHandler = ActionHandler.cmd {
        case RegisterTask(task) ⇒ Persist(TaskRegistered(task))
        case TaskDone(task)     ⇒ Persist(TaskRemoved(task))
      },
      onEvent = (evt, state) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      }
    ).snapshotOnState(_.tasksInFlight.isEmpty)
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
      commandHandler = ActionHandler((cmd, _, ctx) ⇒ cmd match {
        case RegisterTask(task) ⇒ Persist[Event, State](TaskRegistered(task))
          .andThen { _ ⇒
            val child = ctx.spawn[Nothing](worker(task), task)
            // This assumes *any* termination of the child may trigger a `TaskDone`:
            ctx.watchWith(child, TaskDone(task))
          }
        case TaskDone(task) ⇒ Persist(TaskRemoved(task))
      }),
      onEvent = (evt, state) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      }
    )
  }

  object UsingSignals {
    type Task = String
    case class RegisterTask(task: Task)

    sealed trait Event
    case class TaskRegistered(task: Task) extends Event
    case class TaskRemoved(task: Task) extends Event

    case class State(tasksInFlight: List[Task])

    def worker(task: Task): Behavior[Nothing] = ???

    Actor.persistent[RegisterTask, Event, State](
      persistenceId = "asdf",
      initialState = State(Nil),
      // The 'onSignal' seems to break type inference here.. not sure if that can be avoided?
      commandHandler = ActionHandler[RegisterTask, Event, State]((cmd, state, ctx) ⇒ cmd match {
        case RegisterTask(task) ⇒ Persist[Event, State](TaskRegistered(task))
          .andThen { _ ⇒
            val child = ctx.spawn[Nothing](worker(task), task)
            // This assumes *any* termination of the child may trigger a `TaskDone`:
            ctx.watch(child)
          }
      }).onSignal {
        case (Terminated(actorRef), _, ctx) ⇒
          // watchWith (as in the above example) is nicer because it means we don't have to
          // 'manually' associate the task and the child actor, but we wanted to demonstrate
          // signals here:
          Persist(TaskRemoved(actorRef.path.name))
      },
      onEvent = (evt, state) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      }
    )
  }

  object Rehydrating {
    type Id = String

    sealed trait Command
    case class AddItem(id: Id) extends Command
    case class RemoveItem(id: Id) extends Command
    case class GetTotalPrice(sender: ActorRef[Int]) extends Command
    /* Internal: */
    case class GotMetaData(data: MetaData) extends Command

    /**
     * Items have all kinds of metadata, but we only persist the 'id', and
     * rehydrate the metadata on recovery from a registry
     */
    case class Item(id: Id, name: String, price: Int)
    case class Basket(items: Seq[Item]) {
      def updatedWith(data: MetaData): Basket = ???
    }

    sealed trait Event
    case class ItemAdded(id: Id) extends Event
    case class ItemRemoved(id: Id) extends Event

    /*
      * The metadata registry
      */
    case class GetMetaData(id: Id, sender: ActorRef[MetaData])
    case class MetaData(id: Id, name: String, price: Int)
    val metadataRegistry: ActorRef[GetMetaData] = ???

    def isFullyHydrated(basket: Basket, ids: List[Id]) = basket.items.map(_.id) == ids

    akka.typed.scaladsl.Actor.deferred { ctx: ActorContext[Command] ⇒
      var basket = Basket(Nil)
      var stash: Seq[Command] = Nil
      val adapt = ctx.spawnAdapter((m: MetaData) ⇒ GotMetaData(m))

      def addItem(id: Id, self: ActorRef[Command]) =
        Persist[Event, List[Id]](ItemAdded(id)).andThen(_ ⇒
          metadataRegistry ! GetMetaData(id, adapt)
        )

      Actor.persistent[Command, Event, List[Id]](
        persistenceId = "basket-1",
        initialState = Nil,
        commandHandler =
          ActionHandler.byState(state ⇒
            if (isFullyHydrated(basket, state)) ActionHandler { (cmd, state, ctx) ⇒
              cmd match {
                case AddItem(id)    ⇒ addItem(id, ctx.self)
                case RemoveItem(id) ⇒ Persist(ItemRemoved(id))
                case GotMetaData(data) ⇒
                  basket = basket.updatedWith(data); PersistNothing()
                case GetTotalPrice(sender) ⇒ sender ! basket.items.map(_.price).sum; PersistNothing()
              }
            }
            else ActionHandler { (cmd, state, ctx) ⇒
              cmd match {
                case AddItem(id)    ⇒ addItem(id, ctx.self)
                case RemoveItem(id) ⇒ Persist(ItemRemoved(id))
                case GotMetaData(data) ⇒
                  basket = basket.updatedWith(data)
                  if (isFullyHydrated(basket, state)) {
                    stash.foreach(ctx.self ! _)
                    stash = Nil
                  }
                  PersistNothing()
                case cmd: GetTotalPrice ⇒ stash :+= cmd; PersistNothing()
              }
            }
          ),
        onEvent = (evt, state) ⇒ evt match {
          case ItemAdded(id)   ⇒ id +: state
          case ItemRemoved(id) ⇒ state.filter(_ != id)
        }
      ).onRecoveryComplete((ctx, state) ⇒ {
          val ad = ctx.spawnAdapter((m: MetaData) ⇒ GotMetaData(m))
          state.foreach(id ⇒ metadataRegistry ! GetMetaData(id, ad))
        })
    }
  }

  object FactoringOutEventHandling {
    sealed trait Mood
    case object Happy extends Mood
    case object Sad extends Mood

    case object Ack

    sealed trait Command
    case class Greet(name: String) extends Command
    case class CheerUp(sender: ActorRef[Ack.type]) extends Command

    sealed trait Event
    case class MoodChanged(to: Mood) extends Event

    def changeMoodIfNeeded(currentState: Mood, newMood: Mood): PersistentEffect[Event, Mood] =
      if (currentState == newMood) PersistNothing()
      else Persist(MoodChanged(newMood))

    Actor.persistent[Command, Event, Mood](
      persistenceId = "myPersistenceId",
      initialState = Sad,
      commandHandler = ActionHandler { (cmd, state, _) ⇒
        cmd match {
          case Greet(whom) ⇒
            println(s"Hi there, I'm $state!")
            PersistNothing()
          case CheerUp(sender) ⇒
            changeMoodIfNeeded(state, Happy)
              .andThen { _ ⇒ sender ! Ack }
        }
      },
      onEvent = {
        case (MoodChanged(to), _) ⇒ to
      }
    )

  }
}