/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.Done
import akka.actor.typed.{ Behavior, RecipientRef }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.StatePersisted
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.state.RecoveryCompleted
import akka.persistence.typed.state.scaladsl._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.Logger

object UnpersistentDurableStateSpec {
  object BehaviorUnderTest {
    sealed trait Command

    case class Add(n: Int, replyTo: RecipientRef[Done]) extends Command
    case class AddIfLessThan(toAdd: Int, ifLessThan: Int, replyTo: RecipientRef[Boolean]) extends Command
    case class AddWhenAtLeast(toAdd: Int, whenAtLeast: Int, replyTo: RecipientRef[Done]) extends Command
    case class NotifyIfAtLeast(n: Int, notifyTo: RecipientRef[Done], replyTo: RecipientRef[Boolean]) extends Command

    case class State(count: Int, notifyAfter: Map[Int, RecipientRef[Done]], nextNotifyAt: Int) {
      def processAdd(n: Int): State = {
        val nextCount = count + n

        if (nextCount < nextNotifyAt) copy(count = nextCount)
        else {
          import scala.collection.mutable

          val (nextNNA, nextNotifyAfter) = {
            var lowestNotifyAt = Int.MaxValue
            val inProgress = mutable.Map.empty[Int, RecipientRef[Done]]

            notifyAfter.keysIterator.foreach { at =>
              if (at > nextCount) {
                lowestNotifyAt = lowestNotifyAt.min(at)
                inProgress += (at -> notifyAfter(at))
              }
            }

            lowestNotifyAt -> inProgress.toMap
          }

          copy(count = nextCount, notifyAfter = nextNotifyAfter, nextNotifyAt = nextNNA)
        }
      }

      def addObserver(at: Int, notifyTo: RecipientRef[Done]): State = {
        val nextNNA = nextNotifyAt.min(at)
        val nextNotifyAfter = notifyAfter.updated(at, notifyTo)

        copy(notifyAfter = nextNotifyAfter, nextNotifyAt = nextNNA)
      }
    }

    def apply(id: String, recoveryDone: RecipientRef[Done]): Behavior[Command] =
      Behaviors.setup { context =>
        context.setLoggerName(s"entity-$id")

        DurableStateBehavior[Command, State](
          persistenceId = PersistenceId.ofUniqueId(id),
          emptyState = State(0, Map.empty, Int.MaxValue),
          commandHandler = applyCommand(_, _, context.log))
          .receiveSignal {
            case (state, RecoveryCompleted) =>
              context.log.debug("Recovered state for id [{}] is [{}]", id, state)
              recoveryDone ! Done
          }
          .withTag("count")
      }

    private def applyCommand(state: State, cmd: Command, log: Logger): Effect[State] = {
      def persistAdd[Reply](n: Int, replyTo: RecipientRef[Reply], reply: Reply): Effect[State] = {
        val newState = state.processAdd(n)

        Effect
          .persist(newState)
          .thenRun { nextState => // should be the same as newState, but...
            state.notifyAfter.keysIterator
              .filter { at =>
                (at <= nextState.nextNotifyAt) && !(nextState.notifyAfter.isDefinedAt(at))
              }
              .foreach { at =>
                state.notifyAfter(at) ! Done
              }

            replyTo ! reply
          }
          .thenUnstashAll()
      }

      cmd match {
        case Add(n, replyTo) => persistAdd(n, replyTo, Done)

        case AddIfLessThan(toAdd, ifLessThan, replyTo) =>
          if (state.count >= ifLessThan) {
            log.info("Rejecting AddIfLessThan as count = {}", state.count)
            Effect.none[State].thenRun(_ => replyTo ! false)
          } else persistAdd(toAdd, replyTo, true)

        case AddWhenAtLeast(toAdd, whenAtLeast, replyTo) =>
          if (state.count < whenAtLeast) Effect.stash()
          else persistAdd(toAdd, replyTo, Done)

        case NotifyIfAtLeast(n, notifyTo, replyTo) =>
          if (state.count >= n) {
            Effect.none[State].thenRun { _ =>
              notifyTo ! Done
              replyTo ! true
            }
          } else if (state.notifyAfter.isDefinedAt(n)) {
            Effect.none[State].thenRun(_ => replyTo ! false)
          } else {
            Effect.persist(state.addObserver(n, notifyTo)).thenRun(_ => replyTo ! true)
          }
      }
    }
  }
}

class UnpersistentDurableStateSpec extends AnyWordSpec with Matchers {
  import akka.actor.testkit.typed.scaladsl._
  import org.slf4j.event.Level
  import UnpersistentBehavior.drainChangesToList

  import UnpersistentDurableStateSpec._

  "Unpersistent DurableStateBehavior" must {
    "generate an empty Behavior from a non-DurableStateBehavior" in {
      val notDurableState =
        Behaviors.receive[Any] { (context, msg) =>
          context.log.info("Got message {}", msg)
          Behaviors.same
        }

      val (unpersistent, changes) = UnpersistentBehavior.fromDurableState[Any, Any](notDurableState)

      val testkit = BehaviorTestKit(unpersistent)
      testkit.run("Hello there")
      assert(changes.isEmpty, "should be no changes")
      assert(!testkit.hasEffects(), "should be no effects")
      val logs = testkit.logEntries()
      logs.size shouldBe 1
      logs.head.level shouldBe Level.WARN
      logs.head.message shouldBe "Did not find the expected DurableStateBehavior"

      succeed
    }

    "generate a Behavior from a DurableStateBehavior and process RecoveryCompleted" in {
      import BehaviorUnderTest._

      val recoveryDone = TestInbox[Done]()
      val behavior = BehaviorUnderTest("test-1", recoveryDone.ref)

      val (unpersistent, changes) = UnpersistentBehavior.fromDurableState[Command, State](behavior)

      val testkit = BehaviorTestKit(unpersistent)
      assert(changes.isEmpty, "should not be changes yet")
      recoveryDone.expectMessage(Done)
      val logs = testkit.logEntries()
      logs.size shouldBe 1
      logs.head.level shouldBe Level.DEBUG
      logs.head.message shouldBe s"Recovered state for id [test-1] is [${State(0, Map.empty, Int.MaxValue)}]"
    }

    "publish state changes in response to commands" in {
      import BehaviorUnderTest._

      val behavior = BehaviorUnderTest("test-1", TestInbox[Done]().ref)

      val (unpersistent, changes) = UnpersistentBehavior.fromDurableState[Command, State](behavior)

      val testkit = BehaviorTestKit(unpersistent)
      val replyTo = TestInbox[Done]()

      testkit.run(Add(1, replyTo.ref))
      replyTo.expectMessage(Done)
      drainChangesToList(changes) should contain theSameElementsInOrderAs
      Seq(StatePersisted(State(1, Map.empty, Int.MaxValue), 1, Set("count")))
      assert(!testkit.hasEffects(), "should have no effects")
      testkit.clearLog()
    }

    "allow a state to be injected" in {
      import BehaviorUnderTest._

      val behavior = BehaviorUnderTest("test-1", TestInbox[Done]().ref)

      val notify3 = TestInbox[Done]()
      val initialState = State(1, Map(3 -> notify3.ref), 3)

      val (unpersistent, changes) =
        UnpersistentBehavior.fromDurableState[Command, State](behavior, Some(initialState))

      val testkit = BehaviorTestKit(unpersistent)
      val logs = testkit.logEntries()
      logs.size shouldBe 1
      logs.head.level shouldBe Level.DEBUG
      logs.head.message shouldBe s"Recovered state for id [test-1] is [$initialState]"
      assert(changes.isEmpty, "should be no persisted changes")
      assert(!notify3.hasMessages, "no messages should be sent to notify3")

      val replyTo = TestInbox[Done]()
      testkit.run(AddWhenAtLeast(2, 2, replyTo.ref))
      assert(!replyTo.hasMessages, "no messages should be sent now")
      assert(!notify3.hasMessages, "no messages should be sent to notify3")
      assert(changes.isEmpty, "should be no persisted changes")
      assert(!testkit.hasEffects(), "should be no testkit effects")

      testkit.run(Add(3, TestInbox[Done]().ref))
      replyTo.expectMessage(Done)
      notify3.expectMessage(Done)
      assert(!testkit.hasEffects(), "should be no testkit effects")
      drainChangesToList(changes) should contain theSameElementsInOrderAs
      Seq(
        StatePersisted(State(4, Map.empty, Int.MaxValue), 1, Set("count")),
        StatePersisted(State(6, Map.empty, Int.MaxValue), 2, Set("count")))
    }

    "stash and unstash properly" in {
      import BehaviorUnderTest._

      val behavior = BehaviorUnderTest("test-1", TestInbox[Done]().ref)

      val (unpersistent, changes) = UnpersistentBehavior.fromDurableState[Command, State](behavior)

      val replyTo1 = TestInbox[Done]()
      val add = Add(1, TestInbox[Done]().ref)
      val testkit = BehaviorTestKit(unpersistent)

      // stashes
      testkit.run(AddWhenAtLeast(1, 1, replyTo1.ref))
      assert(changes.isEmpty, "should be no persisted changes")
      assert(!replyTo1.hasMessages, "count is not yet 1")

      // unstashes
      testkit.run(add)
      replyTo1.expectMessage(Done)

      // unstash but nothing in the stash
      testkit.run(add)
      assert(!replyTo1.hasMessages, "should not send again")
    }
  }
}
