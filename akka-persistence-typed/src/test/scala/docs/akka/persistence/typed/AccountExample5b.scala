/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehaviors5

/*
API experiment with factory for command and event handler
- same as AccountExample5 but with explicit types rather than type alias
- no enclosing class
- nothing special at all, using defs for different command handlers
- using PersistentBehaviors5
*/

object AccountExample5b {

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

    sealed trait Account {
      def applyEvent(event: AccountEvent): Account
    }
    case object EmptyAccount extends Account {
      override def applyEvent(event: AccountEvent): Account = event match {
        case AccountCreated ⇒ OpenedAccount(0.0)
        case _              ⇒ throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
      }
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

    private def initialHandler(cmd: AccountCommand): Effect[AccountEvent, Account] =
      cmd match {
        case CreateAccount ⇒ Effect.persist(AccountCreated)
        case _             ⇒ Effect.unhandled
      }

    private def openedAccountHandler(acc: OpenedAccount, cmd: AccountCommand): Effect[AccountEvent, Account] = {
      cmd match {
        case Deposit(amount) ⇒ Effect.persist(Deposited(amount))

        case Withdraw(amount) ⇒
          if ((acc.balance - amount) < 0.0)
            Effect.unhandled // TODO replies are missing in this example
          else {
            Effect
              .persist(Withdrawn(amount))
              .thenRun {
                case OpenedAccount(balance) ⇒
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

    private def closedHandler(cmd: AccountCommand): Effect[AccountEvent, Account] =
      Effect.unhandled

    // slightly more verbose than the CommandHandler type alias, but it's more clear that it's just a function

    private val commandHandler: (Account, AccountCommand) ⇒ Effect[AccountEvent, Account] = {
      (state, cmd) ⇒
        state match {
          case EmptyAccount              ⇒ initialHandler(cmd)
          case opened @ OpenedAccount(_) ⇒ openedAccountHandler(opened, cmd)
          case ClosedAccount             ⇒ closedHandler(cmd)
        }
    }

    private val eventHandler: (Account, AccountEvent) ⇒ Account = {
      (state, event) ⇒ state.applyEvent(event)
    }

    // Note that after defining command, event and state classes you would probably start here when writing this.
    // When filling in th parameters of PersistentBehaviors5.receive you can use IntelliJ alt+Enter > createValue
    // to generate the stub with types for the command and event handlers.

    def behavior(accountNumber: String): Behavior[AccountEntity.AccountCommand] = {
      PersistentBehaviors5.receive(
        accountNumber,
        EmptyAccount,
        commandHandler,
        eventHandler
      )
    }

  }

}

