/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.persistence.scaladsl

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.Terminated
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.ActorContext
import akka.typed.scaladsl.TimerScheduler

object PersistentActorCompileOnlyTest {

  import akka.typed.persistence.scaladsl.PersistentActor._

  object Simple {
    sealed trait MyCommand
    case class Cmd(data: String) extends MyCommand

    sealed trait MyEvent
    case class Evt(data: String) extends MyEvent

    case class ExampleState(events: List[String] = Nil)

    PersistentActor.immutable[MyCommand, MyEvent, ExampleState](
      persistenceId = "sample-id-1",

      initialState = ExampleState(Nil),

      actions = Actions.command {
        case Cmd(data) ⇒ Persist(Evt(data))
      },

      applyEvent = {
        case (Evt(data), state) ⇒ state.copy(data :: state.events)
      })
  }

  object WithAck {
    case object Ack

    sealed trait MyCommand
    case class Cmd(data: String, sender: ActorRef[Ack.type]) extends MyCommand

    sealed trait MyEvent
    case class Evt(data: String) extends MyEvent

    case class ExampleState(events: List[String] = Nil)

    PersistentActor.immutable[MyCommand, MyEvent, ExampleState](
      persistenceId = "sample-id-1",

      initialState = ExampleState(Nil),

      actions = Actions.command {
        case Cmd(data, sender) ⇒
          Persist(Evt(data))
            .andThen { sender ! Ack }
      },

      applyEvent = {
        case (Evt(data), state) ⇒ state.copy(data :: state.events)
      })
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
      import akka.typed.scaladsl.AskPattern._
      implicit val timeout: akka.util.Timeout = 1.second
      implicit val scheduler: akka.actor.Scheduler = ???
      implicit val ec: ExecutionContext = ???

      (sideEffectProcessor ? (Request(correlationId, data, _: ActorRef[Response])))
        .map(response ⇒ AcknowledgeSideEffect(response.correlationId))
        .foreach(sender ! _)
    }

    PersistentActor.immutable[Command, Event, EventsInFlight](
      persistenceId = "recovery-complete-id",

      initialState = EventsInFlight(0, Map.empty),

      actions = Actions((ctx, cmd, state) ⇒ cmd match {
        case DoSideEffect(data) ⇒
          Persist(IntentRecorded(state.nextCorrelationId, data)).andThen {
            performSideEffect(ctx.self, state.nextCorrelationId, data)
          }
        case AcknowledgeSideEffect(correlationId) ⇒
          Persist(SideEffectAcknowledged(correlationId))
      }),

      applyEvent = (evt, state) ⇒ evt match {
        case IntentRecorded(correlationId, data) ⇒
          EventsInFlight(
            nextCorrelationId = correlationId + 1,
            dataByCorrelationId = state.dataByCorrelationId + (correlationId → data))
        case SideEffectAcknowledged(correlationId) ⇒
          state.copy(dataByCorrelationId = state.dataByCorrelationId - correlationId)
      }).onRecoveryCompleted {
        case (ctx, state) ⇒ {
          state.dataByCorrelationId.foreach {
            case (correlationId, data) ⇒ performSideEffect(ctx.self, correlationId, data)
          }
          state
        }
      }

  }

  object Become {
    sealed trait Mood
    case object Happy extends Mood
    case object Sad extends Mood

    sealed trait Command
    case class Greet(name: String) extends Command
    case object MoodSwing extends Command

    sealed trait Event
    case class MoodChanged(to: Mood) extends Event

    val b: Behavior[Command] = PersistentActor.immutable[Command, Event, Mood](
      persistenceId = "myPersistenceId",
      initialState = Happy,
      actions = Actions.byState {
        case Happy ⇒ Actions.command {
          case Greet(whom) ⇒
            println(s"Super happy to meet you $whom!")
            PersistNothing()
          case MoodSwing ⇒ Persist(MoodChanged(Sad))
        }
        case Sad ⇒ Actions.command {
          case Greet(whom) ⇒
            println(s"hi $whom")
            PersistNothing()
          case MoodSwing ⇒ Persist(MoodChanged(Happy))
        }
      },
      applyEvent = {
        case (MoodChanged(to), _) ⇒ to
      })

    // FIXME this doesn't work, wrapping is not supported
    Actor.withTimers((timers: TimerScheduler[Command]) ⇒ {
      timers.startPeriodicTimer("swing", MoodSwing, 10.seconds)
      b
    })
  }

  object ExplicitSnapshots {
    type Task = String

    sealed trait Command
    case class RegisterTask(task: Task) extends Command
    case class TaskDone(task: Task) extends Command

    sealed trait Event
    case class TaskRegistered(task: Task) extends Event
    case class TaskRemoved(task: Task) extends Event

    case class State(tasksInFlight: List[Task])

    PersistentActor.immutable[Command, Event, State](
      persistenceId = "asdf",
      initialState = State(Nil),
      actions = Actions.command {
        case RegisterTask(task) ⇒ Persist(TaskRegistered(task))
        case TaskDone(task)     ⇒ Persist(TaskRemoved(task))
      },
      applyEvent = (evt, state) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      }).snapshotOnState(_.tasksInFlight.isEmpty)
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

    PersistentActor.immutable[Command, Event, State](
      persistenceId = "asdf",
      initialState = State(Nil),
      actions = Actions((ctx, cmd, _) ⇒ cmd match {
        case RegisterTask(task) ⇒ Persist(TaskRegistered(task))
          .andThen {
            val child = ctx.spawn[Nothing](worker(task), task)
            // This assumes *any* termination of the child may trigger a `TaskDone`:
            ctx.watchWith(child, TaskDone(task))
          }
        case TaskDone(task) ⇒ Persist(TaskRemoved(task))
      }),
      applyEvent = (evt, state) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      })
  }

  object UsingSignals {
    type Task = String
    case class RegisterTask(task: Task)

    sealed trait Event
    case class TaskRegistered(task: Task) extends Event
    case class TaskRemoved(task: Task) extends Event

    case class State(tasksInFlight: List[Task])

    def worker(task: Task): Behavior[Nothing] = ???

    PersistentActor.immutable[RegisterTask, Event, State](
      persistenceId = "asdf",
      initialState = State(Nil),
      // The 'onSignal' seems to break type inference here.. not sure if that can be avoided?
      actions = Actions[RegisterTask, Event, State]((ctx, cmd, state) ⇒ cmd match {
        case RegisterTask(task) ⇒ Persist(TaskRegistered(task))
          .andThen {
            val child = ctx.spawn[Nothing](worker(task), task)
            // This assumes *any* termination of the child may trigger a `TaskDone`:
            ctx.watch(child)
          }
      }).onSignal {
        case (ctx, Terminated(actorRef), _) ⇒
          // watchWith (as in the above example) is nicer because it means we don't have to
          // 'manually' associate the task and the child actor, but we wanted to demonstrate
          // signals here:
          Persist(TaskRemoved(actorRef.path.name))
      },
      applyEvent = (evt, state) ⇒ evt match {
        case TaskRegistered(task) ⇒ State(task :: state.tasksInFlight)
        case TaskRemoved(task)    ⇒ State(state.tasksInFlight.filter(_ != task))
      })
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

    Actor.deferred { ctx: ActorContext[Command] ⇒
      // FIXME this doesn't work, wrapping not supported

      var basket = Basket(Nil)
      var stash: Seq[Command] = Nil
      val adapt = ctx.spawnAdapter((m: MetaData) ⇒ GotMetaData(m))

      def addItem(id: Id, self: ActorRef[Command]) =
        Persist[Event, List[Id]](ItemAdded(id))
          .andThen(metadataRegistry ! GetMetaData(id, adapt))

      PersistentActor.immutable[Command, Event, List[Id]](
        persistenceId = "basket-1",
        initialState = Nil,
        actions =
          Actions.byState(state ⇒
            if (isFullyHydrated(basket, state)) Actions { (ctx, cmd, state) ⇒
              cmd match {
                case AddItem(id)    ⇒ addItem(id, ctx.self)
                case RemoveItem(id) ⇒ Persist(ItemRemoved(id))
                case GotMetaData(data) ⇒
                  basket = basket.updatedWith(data); PersistNothing()
                case GetTotalPrice(sender) ⇒ sender ! basket.items.map(_.price).sum; PersistNothing()
              }
            }
            else Actions { (ctx, cmd, state) ⇒
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
            }),
        applyEvent = (evt, state) ⇒ evt match {
          case ItemAdded(id)   ⇒ id +: state
          case ItemRemoved(id) ⇒ state.filter(_ != id)
        }).onRecoveryCompleted((ctx, state) ⇒ {
          val ad = ctx.spawnAdapter((m: MetaData) ⇒ GotMetaData(m))
          state.foreach(id ⇒ metadataRegistry ! GetMetaData(id, ad))
          state
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
    case class Remember(memory: String) extends Command

    sealed trait Event
    case class MoodChanged(to: Mood) extends Event
    case class Remembered(memory: String) extends Event

    def changeMoodIfNeeded(currentState: Mood, newMood: Mood): Effect[Event, Mood] =
      if (currentState == newMood) PersistNothing()
      else Persist(MoodChanged(newMood))

    PersistentActor.immutable[Command, Event, Mood](
      persistenceId = "myPersistenceId",
      initialState = Sad,
      actions = Actions { (_, cmd, state) ⇒
        cmd match {
          case Greet(whom) ⇒
            println(s"Hi there, I'm $state!")
            PersistNothing()
          case CheerUp(sender) ⇒
            changeMoodIfNeeded(state, Happy)
              .andThen { sender ! Ack }
          case Remember(memory) ⇒
            // A more elaborate example to show we still have full control over the effects
            // if needed (e.g. when some logic is factored out but you want to add more effects)
            val commonEffects = changeMoodIfNeeded(state, Happy)
            CompositeEffect(
              PersistAll[Event, Mood](commonEffects.events :+ Remembered(memory)),
              commonEffects.sideEffects)

        }
      },
      applyEvent = {
        case (MoodChanged(to), _)   ⇒ to
        case (Remembered(_), state) ⇒ state
      })

  }

  object Stopping {
    sealed trait Command
    case object Enough extends Command

    sealed trait Event
    case object Done extends Event

    type State = Unit

    PersistentActor.immutable[Command, Event, State](
      persistenceId = "myPersistenceId",
      initialState = (),
      actions = Actions { (_, cmd, _) ⇒
        cmd match {
          case Enough ⇒
            Persist(Done)
              .andThen(
                SideEffect(_ ⇒ println("yay")),
                Stop())
        }
      },
      applyEvent = {
        case (Done, _) ⇒ ()
      })
  }

}
