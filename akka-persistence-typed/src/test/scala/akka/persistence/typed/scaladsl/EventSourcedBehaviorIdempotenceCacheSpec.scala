/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.{ CheckIdempotencyKeyExistsSucceeded, PersistenceId, WriteIdempotencyKeySucceeded }
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object EventSourcedBehaviorIdempotenceCacheSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
    """)

  private sealed trait Command
  private case class SideEffect(
      override val idempotencyKey: String,
      override val replyTo: ActorRef[IdempotenceReply[AllGood.type, Int]])
      extends Command
      with IdempotentCommand[AllGood.type, Int]

  private object NoSideEffect {
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
  private case class CurrentIdempotencyKeyCacheContent(replyTo: ActorRef[Seq[String]]) extends Command

  private case object AllGood

  private def idempotentState(
      persistenceId: PersistenceId,
      checks: ActorRef[String],
      writes: ActorRef[String]): Behavior[Command] =
    Behaviors.setup { ctx =>
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
              case CurrentIdempotencyKeyCacheContent(replyTo) =>
                Effect.none[Int, Int].thenReply(replyTo)(_ => EventSourcedBehavior.idempotencyKeyCacheContent(ctx))
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
        .idempotenceKeyCacheSize(3)
    }
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
      val idempotencyKeyCacheProbe = TestProbe[Seq[String]]

      val persistenceId = nextPid
      val idempotenceKey = UUID.randomUUID().toString

      val c = spawn(idempotentState(persistenceId, checksProbe.ref, writesProbe.ref))

      c ! SideEffect(idempotenceKey, probe.ref)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey 1 written")

      c ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq(idempotenceKey))

      c ! SideEffect(idempotenceKey, probe.ref)
      checksProbe.expectNoMessage(3.seconds)
      writesProbe.expectNoMessage(3.seconds)

      c ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq(idempotenceKey))
    }
  }

  "not side-effecting idempotent command if key should always write" should {
    "cache idempotency keys" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val probe = TestProbe[IdempotenceReply[AllGood.type, Int]]
      val idempotencyKeyCacheProbe = TestProbe[Seq[String]]

      val persistenceId = nextPid
      val idempotenceKey = UUID.randomUUID().toString

      val c = spawn(idempotentState(persistenceId, checksProbe.ref, writesProbe.ref))

      c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
      checksProbe.expectMessage(s"$idempotenceKey not exists")
      writesProbe.expectMessage(s"$idempotenceKey 1 written")

      c ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq(idempotenceKey))

      c ! NoSideEffect.WriteAlways(idempotenceKey, probe.ref)
      checksProbe.expectNoMessage(3.seconds)
      writesProbe.expectNoMessage(3.seconds)

      c ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq(idempotenceKey))
    }
  }

  "LRU cache" should {
    "cache and restore" in {
      val checksProbe = createTestProbe[String]()
      val writesProbe = createTestProbe[String]()

      val probe = TestProbe[IdempotenceReply[AllGood.type, Int]]
      val idempotencyKeyCacheProbe = TestProbe[Seq[String]]

      val persistenceId = nextPid

      val c = spawn(idempotentState(persistenceId, checksProbe.ref, writesProbe.ref))

      c ! SideEffect("k1", probe.ref)
      checksProbe.expectMessage(s"k1 not exists")
      writesProbe.expectMessage(s"k1 1 written")
      c ! SideEffect("k2", probe.ref)
      checksProbe.expectMessage(s"k2 not exists")
      writesProbe.expectMessage(s"k2 2 written")
      c ! SideEffect("k3", probe.ref)
      checksProbe.expectMessage(s"k3 not exists")
      writesProbe.expectMessage(s"k3 3 written")
      c ! SideEffect("k4", probe.ref)
      checksProbe.expectMessage(s"k4 not exists")
      writesProbe.expectMessage(s"k4 4 written")

      c ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq("k2", "k3", "k4"))

      c ! SideEffect("k3", probe.ref)
      c ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq("k2", "k4", "k3"))

      val r = spawn(idempotentState(persistenceId, checksProbe.ref, writesProbe.ref))

      Thread.sleep(500)

      r ! CurrentIdempotencyKeyCacheContent(idempotencyKeyCacheProbe.ref)
      idempotencyKeyCacheProbe.expectMessage(Seq("k2", "k3", "k4"))
    }
  }
}
