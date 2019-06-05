/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior

/**
 * Bank account example illustrating:
 * - Option[State] that is starting with None as the initial state
 * - event handlers in the state classes
 * - command handlers in the state classes
 * - replies of various types, using ExpectingReply and withEnforcedReplies
 */
object AccountExampleWithOptionState {

  //#account-entity
  object AccountEntity {
    // Command
    sealed trait AccountCommand[Reply] extends ExpectingReply[Reply]
    final case class CreateAccount()(override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]
    final case class Deposit(amount: BigDecimal)(override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]
    final case class Withdraw(amount: BigDecimal)(override val replyTo: ActorRef[OperationResult])
        extends AccountCommand[OperationResult]
    final case class GetBalance()(override val replyTo: ActorRef[CurrentBalance]) extends AccountCommand[CurrentBalance]
    final case class CloseAccount()(override val replyTo: ActorRef[OperationResult])
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
    type ReplyEffect = akka.persistence.typed.scaladsl.ReplyEffect[AccountEvent, Option[Account]]

    // State
    sealed trait Account {
      def applyCommand(cmd: AccountCommand[_]): ReplyEffect
      def applyEvent(event: AccountEvent): Account
    }
    case class OpenedAccount(balance: BigDecimal) extends Account {
      require(balance >= Zero, "Account balance can't be negative")

      override def applyCommand(cmd: AccountCommand[_]): ReplyEffect =
        cmd match {
          case c @ Deposit(amount) =>
            Effect.persist(Deposited(amount)).thenReply(c)(_ => Confirmed)

          case c @ Withdraw(amount) =>
            if (canWithdraw(amount)) {
              Effect.persist(Withdrawn(amount)).thenReply(c)(_ => Confirmed)

            } else {
              Effect.reply(c)(Rejected(s"Insufficient balance $balance to be able to withdraw $amount"))
            }

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

    def behavior(accountNumber: String): Behavior[AccountCommand[AccountCommandReply]] = {
      EventSourcedBehavior.withEnforcedReplies[AccountCommand[AccountCommandReply], AccountEvent, Option[Account]](
        PersistenceId(s"Account|$accountNumber"),
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

    def onFirstCommand(cmd: AccountCommand[_]): ReplyEffect = {
      cmd match {
        case c: CreateAccount =>
          Effect.persist(AccountCreated).thenReply(c)(_ => Confirmed)
        case _ =>
          // CreateAccount before handling any other commands
          Effect.unhandled.thenNoReply()
      }
    }

    def onFirstEvent(event: AccountEvent): Account = {
      event match {
        case AccountCreated => OpenedAccount(Zero)
        case _              => throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
      }
    }

  }
  //#account-entity

}
