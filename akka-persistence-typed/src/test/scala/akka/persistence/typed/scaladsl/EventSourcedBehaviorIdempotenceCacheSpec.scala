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

object EventSourcedBehaviorIdempotenceCacheSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
    """)

  sealed trait Command
  case class SideEffect(
      override val idempotencyKey: String,
      override val replyTo: ActorRef[IdempotenceReply[AllGood.type, Int]])
      extends Command
      with IdempotentCommand[AllGood.type, Int]

  object NoSideEffect {
    case class WriteAlways(
        override val idempotencyKey: String,
        override val replyTo: ActorRef[IdempotenceReply[AllGood.type, Int]])
        extends Command
        with IdempotentCommand[AllGood.type, Int]

    case class WriteOnlyWithPersist(
        override val idempotencyKey: String,
        override val replyTo: ActorRef[IdempotenceReply[AllGood.type, Int]])
        extends Command
        with IdempotentCommand[AllGood.type, Int] {
      override val writeConfig: IdempotenceKeyWriteConfig = OnlyWriteIdempotenceKeyWithPersist
    }
  }

  case object AllGood

  def idempotentState(
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
              Effect.persist(1).thenReply(replyTo)(_ => IdempotenceSuccess(AllGood))
            case NoSideEffect.WriteAlways(_, replyTo) =>
              Effect.none[Int, Int].thenReply(replyTo)(_ => IdempotenceSuccess(AllGood))
            case NoSideEffect.WriteOnlyWithPersist(_, replyTo) =>
              Effect.none[Int, Int].thenReply(replyTo)(_ => IdempotenceSuccess(AllGood))
          }
        },
        eventHandler = (state, event) => {
          state + event
        })
      .receiveSignal {
        case (_, CheckIdempotencyKeyExistsSucceeded(idempotencyKey, exists)) =>
          checks ! s"$idempotencyKey ${if (exists) "exists" else "not exists"}"
        case (_, WriteIdempotencyKeySucceeded(idempotencyKey, sequenceNumber)) =>
          writes ! s"$idempotencyKey $sequenceNumber written"
      }
      .idempotenceKeyCacheSize(1)
}

class EventSourcedBehaviorIdempotenceCacheSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorIdempotenceCacheSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {
  import EventSourcedBehaviorIdempotenceCacheSpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

  "side-effecting idempotent command" should {
    "cache idempotency keys" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val probe = TestProbe[IdempotenceReply[AllGood.type, Int]]

      val persistenceId = nextPid
      val idempotenceKey = UUID.randomUUID().toString

      val c = spawn(idempotentState(persistenceId, checksProbe.ref, writesProbe.ref))

      c ! SideEffect(idempotenceKey, probe.ref)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey 1 written")
      c ! SideEffect(idempotenceKey, probe.ref)
      checksProbe.expectNoMessage(3.seconds)
    }
  }

  "not side-effecting idempotent command" should {
    "cache idempotency keys" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val probe = TestProbe[IdempotenceReply[AllGood.type, Int]]

      val persistenceId = nextPid
      val idempotenceKey = UUID.randomUUID().toString

      val c = spawn(idempotentState(persistenceId, checksProbe.ref, writesProbe.ref))

      c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey 1 written")
      c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
      checksProbe.expectNoMessage(3.seconds)
    }
  }

  //TODO cache recovery test
}
