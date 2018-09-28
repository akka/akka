/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.persistence.typed.scaladsl.PersistentBehavior.CommandHandler

object AccountExample2 {

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

  private val initialHandler: CommandHandler[AccountCommand, AccountEvent, Account] =
    CommandHandler.command {
      case CreateAccount ⇒ Effect.persist(AccountCreated)
      case _             ⇒ Effect.unhandled
    }

  private val openedAccountHandler: CommandHandler[AccountCommand, AccountEvent, Account] = {
    case (acc: OpenedAccount, cmd) ⇒ cmd match {
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
    case _ ⇒ throw new IllegalStateException
  }

  private val closedHandler: CommandHandler[AccountCommand, AccountEvent, Account] =
    CommandHandler.command(_ ⇒ Effect.unhandled)

  private def commandHandler: CommandHandler[AccountCommand, AccountEvent, Account] = { (state, command) ⇒
    state match {
      case EmptyAccount     ⇒ initialHandler(state, command)
      case OpenedAccount(_) ⇒ openedAccountHandler(state, command)
      case ClosedAccount    ⇒ closedHandler(state, command)
    }
  }

  private val eventHandler: (Account, AccountEvent) ⇒ Account =
    (state, event) ⇒ state.applyEvent(event)

  def behavior(accountNumber: String): Behavior[AccountCommand] =
    PersistentBehavior[AccountCommand, AccountEvent, Account](
      persistenceId = accountNumber,
      emptyState = EmptyAccount,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

