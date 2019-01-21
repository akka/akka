/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.{ Behavior, SupervisorStrategy }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import scala.concurrent.duration._

import akka.persistence.typed.PersistenceId

object BasicPersistentBehaviorCompileOnly {

  object FirstExample {
    //#command
    sealed trait Command
    final case class Add(data: String) extends Command
    case object Clear extends Command

    sealed trait Event
    final case class Added(data: String) extends Event
    case object Cleared extends Event
    //#command

    //#state
    final case class State(history: List[String] = Nil)
    //#state

    //#command-handler
    import akka.persistence.typed.scaladsl.Effect

    val commandHandler: (State, Command) ⇒ Effect[Event, State] = {
      (state, command) ⇒
        command match {
          case Add(data) ⇒ Effect.persist(Added(data))
          case Clear     ⇒ Effect.persist(Cleared)
        }
    }
    //#command-handler

    //#event-handler
    val eventHandler: (State, Event) ⇒ State = {
      (state, event) ⇒
        event match {
          case Added(data) ⇒ state.copy((data :: state.history).take(5))
          case Cleared     ⇒ State(Nil)
        }
    }
    //#event-handler

    //#behavior
    def behavior(id: String): EventSourcedBehavior[Command, Event, State] =
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId(id),
        emptyState = State(Nil),
        commandHandler = commandHandler,
        eventHandler = eventHandler)
    //#behavior

  }

  //#structure
  sealed trait Command
  sealed trait Event
  final case class State()

  val behavior: Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId("abc"),
      emptyState = State(),
      commandHandler =
        (state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    )
  //#structure

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId("abc"),
      emptyState = State(),
      commandHandler =
        (state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    ).onRecoveryCompleted { state ⇒
        throw new RuntimeException("TODO: add some end-of-recovery side-effect here")
      }
  //#recovery

  //#tagging
  val taggingBehavior: Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId("abc"),
      emptyState = State(),
      commandHandler =
        (state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    ).withTagger(_ ⇒ Set("tag1", "tag2"))
  //#tagging

  //#wrapPersistentBehavior
  val samplePersistentBehavior = EventSourcedBehavior[Command, Event, State](
    persistenceId = PersistenceId("abc"),
    emptyState = State(),
    commandHandler =
      (state, cmd) ⇒
        throw new RuntimeException("TODO: process the command & return an Effect"),
    eventHandler =
      (state, evt) ⇒
        throw new RuntimeException("TODO: process the event return the next state")
  ).onRecoveryCompleted { state ⇒
      throw new RuntimeException("TODO: add some end-of-recovery side-effect here")
    }

  val debugAlwaysSnapshot: Behavior[Command] = Behaviors.setup {
    context ⇒
      samplePersistentBehavior.snapshotWhen((state, _, _) ⇒ {
        context.log.info(
          "Snapshot actor {} => state: {}",
          context.self.path.name, state)
        true
      })
  }
  //#wrapPersistentBehavior

  //#supervision
  val supervisedBehavior = samplePersistentBehavior.onPersistFailure(
    SupervisorStrategy.restartWithBackoff(
      minBackoff = 10.seconds,
      maxBackoff = 60.seconds,
      randomFactor = 0.1
    ))
  //#supervision

  // #actor-context
  import akka.persistence.typed.scaladsl.Effect
  import akka.persistence.typed.scaladsl.EventSourcedBehavior.CommandHandler

  val behaviorWithContext: Behavior[String] =
    Behaviors.setup { context ⇒
      EventSourcedBehavior[String, String, State](
        persistenceId = PersistenceId("myPersistenceId"),
        emptyState = new State,
        commandHandler = CommandHandler.command {
          cmd ⇒
            context.log.info("Got command {}", cmd)
            Effect.persist(cmd).thenRun { state ⇒
              context.log.info("event persisted, new state {}", state)
            }
        },
        eventHandler = {
          case (state, _) ⇒ state
        })
    }
  // #actor-context

  //#snapshottingEveryN
  val snapshottingEveryN = EventSourcedBehavior[Command, Event, State](
    persistenceId = PersistenceId("abc"),
    emptyState = State(),
    commandHandler =
      (state, cmd) ⇒
        throw new RuntimeException("TODO: process the command & return an Effect"),
    eventHandler =
      (state, evt) ⇒
        throw new RuntimeException("TODO: process the event return the next state")
  ).snapshotEvery(100)
  //#snapshottingEveryN

  final case class BookingCompleted(orderNr: String) extends Event
  //#snapshottingPredicate
  val snapshottingPredicate = EventSourcedBehavior[Command, Event, State](
    persistenceId = PersistenceId("abc"),
    emptyState = State(),
    commandHandler =
      (state, cmd) ⇒
        throw new RuntimeException("TODO: process the command & return an Effect"),
    eventHandler =
      (state, evt) ⇒
        throw new RuntimeException("TODO: process the event return the next state")
  ).snapshotWhen {
      case (state, BookingCompleted(_), sequenceNumber) ⇒ true
      case (state, event, sequenceNumber)               ⇒ false
    }
  //#snapshottingPredicate

  //#snapshotSelection
  import akka.persistence.SnapshotSelectionCriteria

  val snapshotSelection = EventSourcedBehavior[Command, Event, State](
    persistenceId = PersistenceId("abc"),
    emptyState = State(),
    commandHandler =
      (state, cmd) ⇒
        throw new RuntimeException("TODO: process the command & return an Effect"),
    eventHandler =
      (state, evt) ⇒
        throw new RuntimeException("TODO: process the event return the next state")
  ).withSnapshotSelectionCriteria(SnapshotSelectionCriteria.None)
  //#snapshotSelection

}
