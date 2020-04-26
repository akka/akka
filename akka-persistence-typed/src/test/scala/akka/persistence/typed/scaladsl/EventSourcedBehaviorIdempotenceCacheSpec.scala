/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object EventSourcedBehaviorIdempotenceCacheSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
    """)

  sealed trait Command
  case class SideEffect(override val idempotencyKey: String, override val replyTo: ActorRef[IdempotenceReply])
      extends Command
      with IdempotentCommand

  object NoSideEffect {
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

  case object AllGood extends IdempotenceReply

  def idempotentState(persistenceId: PersistenceId): EventSourcedBehavior[Command, Int, Int] =
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
      val eventProbe = createTestProbe[InmemJournal.Operation]()
      system.eventStream ! EventStream.Subscribe(eventProbe.ref)

      val probe = TestProbe[IdempotenceReply]

      val persistenceId = nextPid
      val c = spawn(idempotentState(persistenceId))

      def withIdempotencyKey(idempotenceKey: String) = {
        c ! SideEffect(idempotenceKey, probe.ref)
        eventProbe.expectMessage(InmemJournal.CheckIdempotenceKeyExists(persistenceId.id, idempotenceKey))
        eventProbe.expectMessageType[InmemJournal.Write]
        eventProbe.expectMessage(InmemJournal.WriteIdempotenceKey(persistenceId.id, idempotenceKey))
        c ! SideEffect(idempotenceKey, probe.ref)
        eventProbe.expectNoMessage(3.seconds)
      }

      withIdempotencyKey(UUID.randomUUID().toString)
      withIdempotencyKey(UUID.randomUUID().toString)
    }
  }

  "not side-effecting idempotent command" should {
    "cache idempotency keys" in {
      val eventProbe = createTestProbe[InmemJournal.Operation]()
      system.eventStream ! EventStream.Subscribe(eventProbe.ref)

      val probe = TestProbe[IdempotenceReply]

      val persistenceId = nextPid
      val c = spawn(idempotentState(persistenceId))

      def withIdempotencyKey(idempotenceKey: String) = {
        c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
        eventProbe.expectMessage(InmemJournal.CheckIdempotenceKeyExists(persistenceId.id, idempotenceKey))
        eventProbe.expectMessage(InmemJournal.WriteIdempotenceKey(persistenceId.id, idempotenceKey))
        c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
        eventProbe.expectNoMessage(3.seconds)
      }

      withIdempotencyKey(UUID.randomUUID().toString)
      withIdempotencyKey(UUID.randomUUID().toString)
    }
  }
}
