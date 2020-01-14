/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.serialization.jackson.CborSerializable

/**
 * Bank account example illustrating:
 * - Option[State] that is starting with None as the initial state
 * - event handlers in the state classes
 * - command handlers in the state classes
 * - replies of various types, using withEnforcedReplies
 */
object AccountExampleWithOptionState {

  //#account-entity
  object AccountEntity {
    // Command
    sealed trait Command[Reply <: CommandReply] extends CborSerializable {
      def replyTo: ActorRef[Reply]
    }
    final case class CreateAccount(replyTo: ActorRef[OperationResult]) extends Command[OperationResult]
    final case class Deposit(amount: BigDecimal, replyTo: ActorRef[OperationResult]) extends Command[OperationResult]
    final case class Withdraw(amount: BigDecimal, replyTo: ActorRef[OperationResult]) extends Command[OperationResult]
    final case class GetBalance(replyTo: ActorRef[CurrentBalance]) extends Command[CurrentBalance]
    final case class CloseAccount(replyTo: ActorRef[OperationResult]) extends Command[OperationResult]

    // Reply
    sealed trait CommandReply extends CborSerializable
    sealed trait OperationResult extends CommandReply
    case object Confirmed extends OperationResult
    final case class Rejected(reason: String) extends OperationResult
    final case class CurrentBalance(balance: BigDecimal) extends CommandReply

    // Event
    sealed trait Event extends CborSerializable
    case object AccountCreated extends Event
    case class Deposited(amount: BigDecimal) extends Event
    case class Withdrawn(amount: BigDecimal) extends Event
    case object AccountClosed extends Event

    val Zero = BigDecimal(0)

    // type alias to reduce boilerplate
    type ReplyEffect = akka.persistence.typed.scaladsl.ReplyEffect[Event, Option[Account]]

    // State
    sealed trait Account extends CborSerializable {
      def applyCommand(cmd: Command[_]): ReplyEffect
      def applyEvent(event: Event): Account
    }
    case class OpenedAccount(balance: BigDecimal) extends Account {
      require(balance >= Zero, "Account balance can't be negative")

      override def applyCommand(cmd: Command[_]): ReplyEffect =
        cmd match {
          case Deposit(amount, replyTo) =>
            Effect.persist(Deposited(amount)).thenReply(replyTo)(_ => Confirmed)

          case Withdraw(amount, replyTo) =>
            if (canWithdraw(amount))
              Effect.persist(Withdrawn(amount)).thenReply(replyTo)(_ => Confirmed)
            else
              Effect.reply(replyTo)(Rejected(s"Insufficient balance $balance to be able to withdraw $amount"))

          case GetBalance(replyTo) =>
            Effect.reply(replyTo)(CurrentBalance(balance))

          case CloseAccount(replyTo) =>
            if (balance == Zero)
              Effect.persist(AccountClosed).thenReply(replyTo)(_ => Confirmed)
            else
              Effect.reply(replyTo)(Rejected("Can't close account with non-zero balance"))

          case CreateAccount(replyTo) =>
            Effect.reply(replyTo)(Rejected("Account is already created"))

        }

      override def applyEvent(event: Event): Account =
        event match {
          case Deposited(amount) => copy(balance = balance + amount)
          case Withdrawn(amount) => copy(balance = balance - amount)
          case AccountClosed     => ClosedAccount
          case AccountCreated    => throw new IllegalStateException(s"unexpected event [$event] in state [OpenedAccount]")
        }

      def canWithdraw(amount: BigDecimal): Boolean = {
        balance - amount >= Zero
      }

    }
    case object ClosedAccount extends Account {
      override def applyCommand(cmd: Command[_]): ReplyEffect =
        cmd match {
          case c @ (_: Deposit | _: Withdraw) =>
            Effect.reply(c.replyTo)(Rejected("Account is closed"))
          case GetBalance(replyTo) =>
            Effect.reply(replyTo)(CurrentBalance(Zero))
          case CloseAccount(replyTo) =>
            Effect.reply(replyTo)(Rejected("Account is already closed"))
          case CreateAccount(replyTo) =>
            Effect.reply(replyTo)(Rejected("Account is already created"))
        }

      override def applyEvent(event: Event): Account =
        throw new IllegalStateException(s"unexpected event [$event] in state [ClosedAccount]")
    }

    // when used with sharding, this TypeKey can be used in `sharding.init` and `sharding.entityRefFor`:
    val TypeKey: EntityTypeKey[Command[_]] =
      EntityTypeKey[Command[_]]("Account")

    def apply(persistenceId: PersistenceId): Behavior[Command[_]] = {
      EventSourcedBehavior.withEnforcedReplies[Command[_], Event, Option[Account]](
        persistenceId,
        None,
        (state, cmd) =>
          state match {
            case None          => onFirstCommand(cmd)
            case Some(account) => account.applyCommand(cmd)
          },
        (state, event) =>
          state match {
            case None          => Some(onFirstEvent(event))
            case Some(account) => Some(account.applyEvent(event))
          })
    }

    def onFirstCommand(cmd: Command[_]): ReplyEffect = {
      cmd match {
        case CreateAccount(replyTo) =>
          Effect.persist(AccountCreated).thenReply(replyTo)(_ => Confirmed)
        case _ =>
          // CreateAccount before handling any other commands
          Effect.unhandled.thenNoReply()
      }
    }

    def onFirstEvent(event: Event): Account = {
      event match {
        case AccountCreated => OpenedAccount(Zero)
        case _              => throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
      }
    }

  }
  //#account-entity

}
