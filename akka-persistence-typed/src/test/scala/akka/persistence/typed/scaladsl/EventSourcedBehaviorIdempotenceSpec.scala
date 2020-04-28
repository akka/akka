/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.ActorRef
import akka.persistence.typed.{ CheckIdempotencyKeyExistsSucceeded, PersistenceId, WriteIdempotencyKeySucceeded }
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object EventSourcedBehaviorIdempotenceSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
    """)

  private sealed trait Command
  private case class SideEffect(override val idempotencyKey: String, override val replyTo: ActorRef[IdempotenceReply])
      extends Command
      with IdempotentCommand

  private object NoSideEffect {
    case class WriteAlways(override val idempotencyKey: String, override val replyTo: ActorRef[IdempotenceReply])
        extends Command
        with IdempotentCommand

    case class WriteOnlyWithPersist(
        override val idempotencyKey: String,
        override val replyTo: ActorRef[IdempotenceReply])
        extends Command
        with IdempotentCommand {
      override val writeConfig: IdempotenceKeyWriteConfig = OnlyWriteIdempotenceKeyWithPersist
    }
  }

  private case object AllGood extends IdempotenceReply

  private def idempotentState(
      persistenceId: PersistenceId,
      checks: ActorRef[String],
      writes: ActorRef[String]): EventSourcedBehavior[Command, Int, Int] =
    EventSourcedBehavior
      .withEnforcedReplies[Command, Int, Int](
        persistenceId,
        emptyState = 0,
        commandHandler = (_, command) => {
          command match {
            case SideEffect(_, replyTo) =>
              Effect.persist(1).thenReply(replyTo)(_ => AllGood)
            case NoSideEffect.WriteAlways(_, replyTo) =>
              Effect.none[Int, Int].thenReply(replyTo)(_ => AllGood)
            case NoSideEffect.WriteOnlyWithPersist(_, replyTo) =>
              Effect.none[Int, Int].thenReply(replyTo)(_ => AllGood)
          }
        },
        eventHandler = (state, event) => {
          state + event
        })
      .receiveSignal {
        case (_, CheckIdempotencyKeyExistsSucceeded(idempotencyKey, exists)) =>
          checks ! s"$idempotencyKey ${if (exists) "exists" else "not exists"}"
        case (_, WriteIdempotencyKeySucceeded(idempotencyKey)) =>
          writes ! s"$idempotencyKey written"
      }
}
class EventSourcedBehaviorIdempotenceSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorIdempotenceSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {
  import EventSourcedBehaviorIdempotenceSpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

  "side-effecting idempotent command" should {
    "consume idempotent command" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val idempotenceKey = UUID.randomUUID().toString
      val c = spawn(idempotentState(nextPid, checksProbe.ref, writesProbe.ref))
      val probe = TestProbe[IdempotenceReply]

      c ! SideEffect(idempotenceKey, probe.ref)
      probe.expectMessage(AllGood)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey written")
    }

    "fail consume idempotent command the second time" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val idempotenceKey = UUID.randomUUID().toString
      val c = spawn(idempotentState(nextPid, checksProbe.ref, writesProbe.ref))
      val probe = TestProbe[IdempotenceReply]

      c ! SideEffect(idempotenceKey, probe.ref)
      probe.expectMessage(AllGood)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey written")

      c ! SideEffect(idempotenceKey, probe.ref)
      probe.expectMessage(IdempotenceFailure)
      checksProbe.expectMessage(s"$idempotenceKey exists")
      writesProbe.expectNoMessage(3.seconds)
    }
  }

  "not side-effecting idempotent command" should {
    "fail consume the second time if key should always write" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val idempotenceKey = UUID.randomUUID().toString
      val c = spawn(idempotentState(nextPid, checksProbe.ref, writesProbe.ref))
      val probe = TestProbe[IdempotenceReply]

      c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
      probe.expectMessage(AllGood)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey written")

      c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
      checksProbe.expectMessage(s"$idempotenceKey exists")
      writesProbe.expectNoMessage(3.seconds)
    }

    "succeed consume the second time if key should write only with persist" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val idempotenceKey = UUID.randomUUID().toString
      val c = spawn(idempotentState(nextPid, checksProbe.ref, writesProbe.ref))
      val probe = TestProbe[IdempotenceReply]

      c ! NoSideEffect.WriteOnlyWithPersist(idempotenceKey, probe.ref)
      probe.expectMessage(AllGood)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectNoMessage(3.seconds)

      c ! NoSideEffect.WriteOnlyWithPersist(idempotenceKey, probe.ref)
      probe.expectMessage(AllGood)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectNoMessage(3.seconds)
    }
  }
}
