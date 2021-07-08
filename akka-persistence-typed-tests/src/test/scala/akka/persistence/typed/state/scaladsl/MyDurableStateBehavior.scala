/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.scaladsl

// import akka.persistence.typed.state.scaladsl.DurableStateBehavior
// import java.util.concurrent.atomic.AtomicInteger

// import com.typesafe.config.Config
// import com.typesafe.config.ConfigFactory
// import org.scalatest.wordspec.AnyWordSpecLike

import akka.Done
// import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
// import akka.actor.typed.Behavior
// import akka.actor.typed.scaladsl.ActorContext
// import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.serialization.jackson.CborSerializable

object MyDurableStateBehavior1 {

  sealed trait Command[ReplyMessage] extends CborSerializable
  final case object Increment extends Command[Nothing]
  case class IncrementBy(value: Int) extends Command[Nothing]
  final case class GetValue(replyTo: ActorRef[State]) extends Command[State]

  final case class State(value: Int) extends CborSerializable

  def counter(persistenceId: PersistenceId): DurableStateBehavior[Command[_], State] = {
    DurableStateBehavior.apply[Command[_], State](
      persistenceId,
      emptyState = State(0),
      commandHandler = (state, command) =>
        command match {
          case Increment =>
            Effect.persist(state.copy(value = state.value + 1))
          case IncrementBy(by) =>
            Effect.persist(state.copy(value = state.value + by))
          case GetValue(replyTo) =>
            Effect.reply(replyTo)(state)
        })
  }
}

object MyDurableStateBehavior2 {

  sealed trait Command[ReplyMessage] extends CborSerializable
  final case class IncrementWithConfirmation(replyTo: ActorRef[Done]) extends Command[Done]
  final case class GetValue(replyTo: ActorRef[State]) extends Command[State]

  final case class State(value: Int) extends CborSerializable

  def counter(persistenceId: PersistenceId): DurableStateBehavior[Command[_], State] = {
    DurableStateBehavior.withEnforcedReplies[Command[_], State](
      persistenceId,
      emptyState = State(0),
      commandHandler = (state, command) =>
        command match {

          case IncrementWithConfirmation(replyTo) =>
            Effect.persist(state.copy(value = state.value + 1)).thenReply(replyTo)(_ => Done)

          case GetValue(replyTo) =>
            Effect.reply(replyTo)(state)
        })
  }
}
