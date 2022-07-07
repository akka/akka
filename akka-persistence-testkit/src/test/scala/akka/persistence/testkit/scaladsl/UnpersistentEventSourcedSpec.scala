/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.Done
import akka.actor.typed.{ Behavior, RecipientRef }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.{ EventPersisted, StatePersisted }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted, SnapshotCompleted, SnapshotMetadata }
import akka.persistence.typed.scaladsl._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.Logger

object UnpersistentEventSourcedSpec {
  object BehaviorUnderTest {
    sealed trait Command

    case class PersistDomainEvent(replyTo: RecipientRef[Done]) extends Command
    case class PersistDomainEventIfBefore(count: Int, replyTo: RecipientRef[Boolean]) extends Command
    case class PersistDomainEventAfter(count: Int, replyTo: RecipientRef[Done]) extends Command
    case class NotifyAfter(count: Int, notifyTo: RecipientRef[Done], replyTo: RecipientRef[Boolean]) extends Command
    case object SnapshotNow extends Command
    
    sealed trait Event
    case object DomainEvent extends Event
    case object SnapshotMade extends Event
    case class ObserverAdded(count: Int, notifyTo: RecipientRef[Done]) extends Event

    case class State(domainEvtCount: Int, notifyAfter: Map[Int, RecipientRef[Done]], nextNotifyAt: Int)

    def apply(
      id: String,
      recoveryDone: RecipientRef[Done],
      snapshotMetaTo: RecipientRef[SnapshotMetadata]
    ): Behavior[Command] =
      Behaviors.setup { context =>
        context.setLoggerName(s"entity-$id")

        EventSourcedBehavior[Command, Event, State](
          persistenceId = PersistenceId.ofUniqueId(id),
          emptyState = State(0, Map.empty, Int.MaxValue),
          commandHandler = applyCommand(_, _, context.log),
          eventHandler = applyEvent(_, _)
        ).receiveSignal {
          case (state, RecoveryCompleted) =>
            context.log.debug("Recovered state for id [{}] is [{}]", id, state)
            recoveryDone ! Done

          case (_, SnapshotCompleted(meta)) =>
            context.log.debug("Snapshot completed with metadata {}", meta)
            snapshotMetaTo ! meta
        }.snapshotWhen {
          case (_, SnapshotMade, _) => true
          case _ => false
        }.withRetention(
          RetentionCriteria.snapshotEvery(numberOfEvents = 3, keepNSnapshots = 2)
        ).withTagger {
          case DomainEvent => Set("domain")
          case _ => Set.empty
        }
      }

    private def applyCommand(state: State, cmd: Command, log: Logger): Effect[Event, State] = {
      def persistDomainEvent[Reply](replyTo: RecipientRef[Reply], reply: Reply): Effect[Event, State] =
        Effect.persist[Event, State](DomainEvent)
          .thenRun { newState =>
            state.notifyAfter.keysIterator
              .filter { at => (at <= newState.nextNotifyAt) && !(newState.notifyAfter.isDefinedAt(at)) }
              .foreach { at =>
                state.notifyAfter(at) ! Done
              }

            replyTo ! reply
          }
          .thenUnstashAll()

      cmd match {
        case PersistDomainEvent(replyTo) => persistDomainEvent(replyTo, Done)

        case PersistDomainEventIfBefore(count, replyTo) =>
          if (state.domainEvtCount >= count) {
            log.info("Rejecting PersistDomainEventIfBefore as domainEvtCount = {}", state.domainEvtCount)
            Effect.none.thenRun(_ => replyTo ! false)
          } else persistDomainEvent(replyTo, true)

        case PersistDomainEventAfter(count, replyTo) =>
          if (state.domainEvtCount < count) Effect.stash()
          else persistDomainEvent(replyTo, Done)

        case NotifyAfter(count, notifyTo, replyTo) =>
          if (state.domainEvtCount >= count) {
            Effect.none.thenRun { _ =>
              notifyTo ! Done
              replyTo ! true
            }
          } else if (state.notifyAfter.isDefinedAt(count)) {
            Effect.none.thenRun(_ => replyTo ! false)
          } else {
            Effect.persist(ObserverAdded(count, notifyTo)).thenRun(_ => replyTo ! true)
          }

        case SnapshotNow => Effect.persist(SnapshotMade)
      }
    }
    
    private[scaladsl] def applyEvent(state: State, evt: Event): State =
      evt match {
        case DomainEvent =>
          val nextDomainEvtCount = state.domainEvtCount + 1
          
          if (nextDomainEvtCount < state.nextNotifyAt) state.copy(domainEvtCount = nextDomainEvtCount)
          else {
            import scala.collection.mutable

            val (nextNNA, nextNotifyAfter) = {
              var lowestNotifyAt = Int.MaxValue
              val inProgress = mutable.Map.empty[Int, RecipientRef[Done]]

              state.notifyAfter.keysIterator.foreach { at =>
                if (at > nextDomainEvtCount) {
                  lowestNotifyAt = lowestNotifyAt.min(at)
                  inProgress += (at -> state.notifyAfter(at))
                }
                // else ()
              }

              lowestNotifyAt -> inProgress.toMap
            }

            state.copy(
              domainEvtCount = nextDomainEvtCount,
              notifyAfter = nextNotifyAfter,
              nextNotifyAt = nextNNA
            )
          }

        case SnapshotMade => state
        case ObserverAdded(count, notifyTo) =>
          val nextNNA = state.nextNotifyAt.min(count)
          val nextNotifyAfter = state.notifyAfter.updated(count, notifyTo)

          state.copy(notifyAfter = nextNotifyAfter, nextNotifyAt = nextNNA)
      }
  }
}

class UnpersistentEventSourcedSpec extends AnyWordSpec with Matchers {
  import akka.actor.testkit.typed.scaladsl._
  import org.slf4j.event.Level
  import UnpersistentBehavior.drainChangesToList

  import UnpersistentEventSourcedSpec._

  "Unpersistent EventSourcedBehavior" must {
    "generate an empty Behavior from a non-EventSourcedBehavior" in {
      val notEventSourced =
        Behaviors.receive[Any] { (context, msg) =>
          context.log.info("Got message {}", msg)
          Behaviors.same
        }

      val (unpersistent, changes) = UnpersistentBehavior.fromEventSourced[Any, Any, Any](notEventSourced)

      val testkit = BehaviorTestKit(unpersistent)
      testkit.run("Hello there")
      assert(changes.isEmpty, "should be no changes")
      assert(!testkit.hasEffects(), "should be no effects")
      val logs = testkit.logEntries()
      logs.size shouldBe 1
      logs.head.level shouldBe Level.WARN
      logs.head.message shouldBe "Did not find the expected EventSourcedBehavior"

      succeed
    }

    "generate a Behavior from an EventSourcedBehavior and process RecoveryCompleted" in {
      import BehaviorUnderTest._

      val recoveryDone = TestInbox[Done]()
      val snapshotMeta = TestInbox[SnapshotMetadata]()
      val behavior = BehaviorUnderTest("test-1", recoveryDone.ref, snapshotMeta.ref)

      val (unpersistent, changes) = UnpersistentBehavior.fromEventSourced[Command, Event, State](behavior)

      val testkit = BehaviorTestKit(unpersistent)
      assert(changes.isEmpty, "should not be changes yet")
      recoveryDone.expectMessage(Done)
      assert(!snapshotMeta.hasMessages, "snapshot should not be made")
      val logs = testkit.logEntries()
      logs.size shouldBe 1
      logs.head.level shouldBe Level.DEBUG
      logs.head.message shouldBe s"Recovered state for id [test-1] is [${State(0, Map.empty, Int.MaxValue)}]"
    }

    "publish events and evolve observed state in response to commands" in {
      import BehaviorUnderTest._

      val snapshotMeta = TestInbox[SnapshotMetadata]()
      val behavior = BehaviorUnderTest("test-1", TestInbox[Done]().ref, snapshotMeta.ref)

      val (unpersistent, changes) = UnpersistentBehavior.fromEventSourced[Command, Event, State](behavior)

      val testkit = BehaviorTestKit(unpersistent)
      testkit.clearLog()
      val replyTo = TestInbox[Done]()

      testkit.run(PersistDomainEvent(replyTo.ref))
      replyTo.expectMessage(Done)
      assert(!snapshotMeta.hasMessages, "snapshot should not be made")
      drainChangesToList(changes) should contain theSameElementsInOrderAs
        Seq(
          EventPersisted(DomainEvent, 1, Set("domain"))
        )
      assert(!testkit.hasEffects(), "should have no effects")
      testkit.clearLog()

      testkit.run(SnapshotNow)
      assert(!replyTo.hasMessages, "should not be a reply")
      val snapshotMetaMsg = snapshotMeta.receiveMessage()
      snapshotMetaMsg.persistenceId shouldBe "PersistenceId(test-1)"
      snapshotMetaMsg.sequenceNr shouldBe 2
      // not checking the timestamp...
      drainChangesToList(changes) should contain theSameElementsInOrderAs
        Seq(
          EventPersisted(SnapshotMade, 2, Set.empty),
          StatePersisted(State(1, Map.empty, Int.MaxValue), 2, Set.empty)
        )
    }

    "allow a state and starting offset to be injected" in {
      import BehaviorUnderTest._

      val recoveryDone = TestInbox[Done]()
      val snapshotMeta = TestInbox[SnapshotMetadata]()
      val behavior = BehaviorUnderTest("test-1", recoveryDone.ref, snapshotMeta.ref)

      val notify3 = TestInbox[Done]()
      val initialState =
        Seq(
          ObserverAdded(3, notify3.ref),
          SnapshotMade,
          DomainEvent,
          DomainEvent
        ).foldLeft(State(0, Map.empty, Int.MaxValue))(applyEvent _)

      val (unpersistent, changes) =
        UnpersistentBehavior.fromEventSourced[Command, Event, State](behavior, Some(initialState -> 41))

      val testkit = BehaviorTestKit(unpersistent)
      recoveryDone.expectMessage(Done)
      assert(!snapshotMeta.hasMessages, "snapshot should not be made")
      val logs = testkit.logEntries()
      logs.size shouldBe 1
      logs.head.level shouldBe Level.DEBUG
      logs.head.message shouldBe s"Recovered state for id [test-1] is [$initialState]"
      assert(changes.isEmpty, "should be no persisted changes")
      assert(!notify3.hasMessages, "no messages should be sent to notify3")

      val replyTo = TestInbox[Done]()
      testkit.run(PersistDomainEventAfter(2, replyTo.ref))
      assert(!testkit.hasEffects(), "should be no testkit effects")
      val snapshotMetaMsg = snapshotMeta.receiveMessage()
      snapshotMetaMsg.persistenceId shouldBe "PersistenceId(test-1)"
      snapshotMetaMsg.sequenceNr shouldBe 42
      drainChangesToList(changes) should contain theSameElementsInOrderAs
        Seq(
          EventPersisted(DomainEvent, 42, Set("domain")),
          StatePersisted(State(3, Map.empty, Int.MaxValue), 42, Set.empty)
        )

      notify3.expectMessage(Done)
      replyTo.expectMessage(Done)
    }

    "stash and unstash properly" in {
      import BehaviorUnderTest._

      val behavior = BehaviorUnderTest("test-1", TestInbox[Done]().ref, TestInbox[SnapshotMetadata]().ref)

      val (unpersistent, changes) =
        UnpersistentBehavior.fromEventSourced[Command, Event, State](behavior, None)

      val replyTo1 = TestInbox[Done]()
      val pde = PersistDomainEvent(TestInbox[Done]().ref)
      val testkit = BehaviorTestKit(unpersistent)

      // stashes
      testkit.run(PersistDomainEventAfter(1, replyTo1.ref))
      drainChangesToList(changes) shouldBe empty
      assert(!replyTo1.hasMessages, "have not persisted first domain event")

      // unstashes
      testkit.run(pde)
      replyTo1.expectMessage(Done)
  
      // unstash, but nothing in the stash
      testkit.run(pde)
      assert(!replyTo1.hasMessages, "should not send again")
    }
  }
}
