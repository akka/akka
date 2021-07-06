/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import akka.Done
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.serialization.jackson.CborSerializable

object DurableStateBehaviorReplySpec {
  def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.persistence.state.plugin = "akka.persistence.query.state.inmem"
    akka.persistence.query.state.inmem {
      class = "akka.persistence.query.state.inmem.InmemDurableStateStoreProvider"
    }
    """)

  sealed trait Command[ReplyMessage] extends CborSerializable
  final case class IncrementWithConfirmation(replyTo: ActorRef[Done]) extends Command[Done]
  final case class IncrementReplyLater(replyTo: ActorRef[Done]) extends Command[Done]
  final case class ReplyNow(replyTo: ActorRef[Done]) extends Command[Done]
  final case class GetValue(replyTo: ActorRef[State]) extends Command[State]

  final case class State(value: Int) extends CborSerializable

  def counter(persistenceId: PersistenceId): Behavior[Command[_]] =
    Behaviors.setup(ctx => counter(ctx, persistenceId))

  def counter(ctx: ActorContext[Command[_]], persistenceId: PersistenceId): DurableStateBehavior[Command[_], State] = {
    DurableStateBehavior.withEnforcedReplies[Command[_], State](
      persistenceId,
      emptyState = State(0),
      commandHandler = (state, command) =>
        command match {

          case IncrementWithConfirmation(replyTo) =>
            Effect.persist(state.copy(value = state.value + 1)).thenReply(replyTo)(_ => Done)

          case IncrementReplyLater(replyTo) =>
            Effect
              .persist(state.copy(value = state.value + 1))
              .thenRun((_: State) => ctx.self ! ReplyNow(replyTo))
              .thenNoReply()

          case ReplyNow(replyTo) =>
            Effect.reply(replyTo)(Done)

          case GetValue(replyTo) =>
            Effect.reply(replyTo)(state)

        })
  }
}

class DurableStateBehaviorReplySpec
    extends ScalaTestWithActorTestKit(DurableStateBehaviorReplySpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  import DurableStateBehaviorReplySpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

  "A typed persistent actor with commands that are expecting replies" must {

    "persist an event thenReply" in {
      val c = spawn(counter(nextPid()))
      val probe = TestProbe[Done]()
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)

      c ! IncrementWithConfirmation(probe.ref)
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)
      probe.expectMessage(Done)
    }

    "persist an event thenReply later" in {
      val c = spawn(counter(nextPid()))
      val probe = TestProbe[Done]()
      c ! IncrementReplyLater(probe.ref)
      probe.expectMessage(Done)
    }

    "reply to query command" in {
      val c = spawn(counter(nextPid()))
      val updateProbe = TestProbe[Done]()
      c ! IncrementWithConfirmation(updateProbe.ref)

      val queryProbe = TestProbe[State]()
      c ! GetValue(queryProbe.ref)
      queryProbe.expectMessage(State(1))
    }
  }
}
