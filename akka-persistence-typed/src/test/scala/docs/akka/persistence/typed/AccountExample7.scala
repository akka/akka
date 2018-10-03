/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehaviors5

/*
API experiment with factory for command and event handler
- optional state
- nothing special at all, using defs for different command handlers
- using PersistentBehaviors5
*/

object AccountExample7 {

  object AccountEntity {
    sealed trait AccountCommand
    case object CreateAccount extends AccountCommand
    case class Deposit(amount: Double) extends AccountCommand
    case class Withdraw(amount: Double) extends AccountCommand
    case object CloseAccount extends AccountCommand

    sealed trait AccountEvent
    case object AccountCreated extends AccountEvent
    case class Deposited(amount: Double) extends AccountEvent
    case class Withdrawn(amount: Double) extends AccountEvent
    case object AccountClosed extends AccountEvent

    object Account {
      def fromFirstEvent(event: AccountEvent): Account = {
        event match {
          case AccountCreated ⇒ OpenedAccount(0.0)
          case _              ⇒ throw new IllegalStateException(s"unexpected first event [$event]")
        }
      }
    }
    sealed trait Account {
      def applyEvent(event: AccountEvent): Account
    }
    case class OpenedAccount(balance: Double) extends Account {
      override def applyEvent(event: AccountEvent): Account = event match {
        case Deposited(amount) ⇒ copy(balance = balance + amount)
        case Withdrawn(amount) ⇒ copy(balance = balance - amount)
        case AccountClosed     ⇒ ClosedAccount
        case _                 ⇒ throw new IllegalStateException(s"unexpected event [$event] in state [OpenedAccount]")
      }
    }
    case object ClosedAccount extends Account {
      override def applyEvent(event: AccountEvent): Account =
        throw new IllegalStateException(s"unexpected event [$event] in state [ClosedAccount]")
    }

    // boilerplate of Effect[AccountEvent, Option[Account]] can be removed with type alias
    // type Effect = Effect[AccountEvent, Option[Account]]

    private def firstCommand(cmd: AccountCommand): Effect[AccountEvent, Option[Account]] =
      cmd match {
        case CreateAccount ⇒ Effect.persist(AccountCreated)
        case _             ⇒ Effect.unhandled
      }

    private def openedAccountHandler(acc: OpenedAccount, cmd: AccountCommand): Effect[AccountEvent, Option[Account]] = {
      cmd match {
        case Deposit(amount) ⇒ Effect.persist(Deposited(amount))

        case Withdraw(amount) ⇒
          if ((acc.balance - amount) < 0.0)
            Effect.unhandled // TODO replies are missing in this example
          else {
            Effect
              .persist(Withdrawn(amount))
              .thenRun {
                case Some(OpenedAccount(balance)) ⇒
                  // do some side-effect using balance
                  println(balance)
                case _ ⇒ throw new IllegalStateException
              }
          }
        case CloseAccount if acc.balance == 0.0 ⇒
          Effect.persist(AccountClosed)

        case CloseAccount ⇒
          Effect.unhandled
      }
    }

    private def closedHandler(cmd: AccountCommand): Effect[AccountEvent, Option[Account]] =
      Effect.unhandled

    def behavior(accountNumber: String): Behavior[AccountEntity.AccountCommand] = {
      PersistentBehaviors5.receive[AccountCommand, AccountEvent, Option[Account]](
        accountNumber,
        None,
        (state, cmd) ⇒
          state match {
            case None                            ⇒ firstCommand(cmd)
            case Some(opened @ OpenedAccount(_)) ⇒ openedAccountHandler(opened, cmd)
            case Some(ClosedAccount)             ⇒ closedHandler(cmd)
          },
        (state, event) ⇒ state match {
          case None          ⇒ Some(Account.fromFirstEvent(event))
          case Some(account) ⇒ Some(account.applyEvent(event))
        }
      )
    }
  }

}

