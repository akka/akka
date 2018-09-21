/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler

object AccountExample1 {

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

  sealed trait Account
  case class OpenedAccount(balance: Double) extends Account
  case object ClosedAccount extends Account

  private val initialHandler: CommandHandler[AccountCommand, AccountEvent, Option[Account]] =
    CommandHandler.command {
      case CreateAccount ⇒ Effect.persist(AccountCreated)
      case _             ⇒ Effect.unhandled
    }

  private val openedAccountHandler: CommandHandler[AccountCommand, AccountEvent, Option[Account]] = {
    case (Some(acc: OpenedAccount), cmd) ⇒ cmd match {
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

      case _ ⇒
        Effect.unhandled
    }
    case _ ⇒ throw new IllegalStateException
  }

  private val closedHandler: CommandHandler[AccountCommand, AccountEvent, Option[Account]] =
    CommandHandler.command(_ ⇒ Effect.unhandled)

  private def commandHandler: CommandHandler[AccountCommand, AccountEvent, Option[Account]] = { (state, command) ⇒
    state match {
      case None                   ⇒ initialHandler(state, command)
      case Some(OpenedAccount(_)) ⇒ openedAccountHandler(state, command)
      case Some(ClosedAccount)    ⇒ closedHandler(state, command)
    }
  }

  private val eventHandler: (Option[Account], AccountEvent) ⇒ Option[Account] = {
    case (None, AccountCreated) ⇒ Some(OpenedAccount(0.0))

    case (Some(acc @ OpenedAccount(_)), Deposited(amount)) ⇒
      Some(acc.copy(balance = acc.balance + amount))

    case (Some(acc @ OpenedAccount(_)), Withdrawn(amount)) ⇒
      Some(acc.copy(balance = acc.balance - amount))

    case (Some(OpenedAccount(_)), AccountClosed) ⇒
      Some(ClosedAccount)

    case (state, event) ⇒ throw new RuntimeException(s"unexpected event [$event] in state [$state]")
  }

  def behavior(accountNumber: String): Behavior[AccountCommand] =
    PersistentBehaviors.receive[AccountCommand, AccountEvent, Option[Account]](
      persistenceId = accountNumber,
      emptyState = None,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

