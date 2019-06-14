/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.scaladsl.EventSourcedEntity
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.scaladsl.Effect

/**
 * Bank account example illustrating:
 * - different state classes representing the lifecycle of the account
 * - event handlers in the state classes
 * - command handlers in the state classes
 * - replies of various types, using ExpectingReply and withEnforcedReplies
 */
object AccountExampleWithCommandHandlersInState {

  //#account-entity
  object AccountEntity {
    // Command
    sealed trait AccountCommand[Reply] extends ExpectingReply[Reply]
    final case class CreateAccount(override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]
    final case class Deposit(amount: BigDecimal, override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]
    final case class Withdraw(amount: BigDecimal, override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]
    final case class GetBalance(override val replyTo: ActorRef[CurrentBalance]) extends AccountCommand[CurrentBalance]
    final case class CloseAccount(override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]

    // Reply
    sealed trait AccountCommandReply
    sealed trait OperationResult extends AccountCommandReply
    case object Confirmed extends OperationResult
    final case class Rejected(reason: String) extends OperationResult
    final case class CurrentBalance(balance: BigDecimal) extends AccountCommandReply

    // Event
    sealed trait AccountEvent
    case object AccountCreated extends AccountEvent
    case class Deposited(amount: BigDecimal) extends AccountEvent
    case class Withdrawn(amount: BigDecimal) extends AccountEvent
    case object AccountClosed extends AccountEvent

    val Zero = BigDecimal(0)

    // type alias to reduce boilerplate
    type ReplyEffect = akka.persistence.typed.scaladsl.ReplyEffect[AccountEvent, Account]

    // State
    sealed trait Account {
      def applyCommand(cmd: AccountCommand[_]): ReplyEffect
      def applyEvent(event: AccountEvent): Account
    }
    case object EmptyAccount extends Account {
      override def applyCommand(cmd: AccountCommand[_]): ReplyEffect =
        cmd match {
          case c: CreateAccount =>
            Effect.persist(AccountCreated).thenReply(c)(_ => Confirmed)
          case _ =>
            // CreateAccount before handling any other commands
            Effect.unhandled.thenNoReply()
        }

      override def applyEvent(event: AccountEvent): Account =
        event match {
          case AccountCreated => OpenedAccount(Zero)
          case _              => throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
        }
    }
    case class OpenedAccount(balance: BigDecimal) extends Account {
      require(balance >= Zero, "Account balance can't be negative")

      override def applyCommand(cmd: AccountCommand[_]): ReplyEffect =
        cmd match {
          case c: Deposit =>
            Effect.persist(Deposited(c.amount)).thenReply(c)(_ => Confirmed)

          case c: Withdraw =>
            if (canWithdraw(c.amount))
              Effect.persist(Withdrawn(c.amount)).thenReply(c)(_ => Confirmed)
            else
              Effect.reply(c)(Rejected(s"Insufficient balance $balance to be able to withdraw ${c.amount}"))

          case c: GetBalance =>
            Effect.reply(c)(CurrentBalance(balance))

          case c: CloseAccount =>
            if (balance == Zero)
              Effect.persist(AccountClosed).thenReply(c)(_ => Confirmed)
            else
              Effect.reply(c)(Rejected("Can't close account with non-zero balance"))

          case c: CreateAccount =>
            Effect.reply(c)(Rejected("Account is already created"))

        }

      override def applyEvent(event: AccountEvent): Account =
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
      override def applyCommand(cmd: AccountCommand[_]): ReplyEffect =
        cmd match {
          case c @ (_: Deposit | _: Withdraw) =>
            Effect.reply(c)(Rejected("Account is closed"))
          case c: GetBalance =>
            Effect.reply(c)(CurrentBalance(Zero))
          case c: CloseAccount =>
            Effect.reply(c)(Rejected("Account is already closed"))
          case c: CreateAccount =>
            Effect.reply(c)(Rejected("Account is already created"))
        }

      override def applyEvent(event: AccountEvent): Account =
        throw new IllegalStateException(s"unexpected event [$event] in state [ClosedAccount]")
    }

    val TypeKey: EntityTypeKey[AccountCommand[_]] =
      EntityTypeKey[AccountCommand[_]]("Account")

    def apply(accountNumber: String): Behavior[AccountCommand[_]] = {
      EventSourcedEntity.withEnforcedReplies[AccountCommand[_], AccountEvent, Account](
        TypeKey,
        accountNumber,
        EmptyAccount,
        (state, cmd) => state.applyCommand(cmd),
        (state, event) => state.applyEvent(event))
    }

  }
  //#account-entity

}
