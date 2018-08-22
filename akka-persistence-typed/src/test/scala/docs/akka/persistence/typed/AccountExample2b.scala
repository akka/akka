/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehavior2

object AccountExample2b {

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

    def behavior(accountNumber: String): Behavior[AccountEntity.AccountCommand] =
      Behaviors.setup(ctx ⇒ new AccountEntity(ctx, accountNumber))
  }

  class AccountEntity(context: ActorContext[AccountEntity.AccountCommand], accountNumber: String)
    extends PersistentBehavior2[AccountEntity.AccountCommand, AccountEntity.AccountEvent, AccountEntity.Account](
      persistenceId = accountNumber) {
    import AccountEntity._

    override protected def emptyState: Account = EmptyAccount

    private val initialHandler: CommandHandler = // the CommandHandler type annotation is not needed when using `command` convenience
      command {
        case CreateAccount ⇒ Effect.persist(AccountCreated)
        case _             ⇒ Effect.unhandled
      }

    private val openedAccountHandler: SubStateCommandHandler[OpenedAccount] = {
      (acc, cmd) ⇒
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

    private val closedHandler: CommandHandler =
      command(_ ⇒ Effect.unhandled)

    override protected def commandHandler(): (Account, AccountCommand) ⇒ Effect[AccountEvent, Account] = {
      (state, cmd) ⇒
        state match {
          case EmptyAccount              ⇒ initialHandler(state, cmd)
          case opened @ OpenedAccount(_) ⇒ openedAccountHandler(opened, cmd)
          case ClosedAccount             ⇒ closedHandler(state, cmd)
        }
    }

    override protected def eventHandler(): (Account, AccountEvent) ⇒ Account = {
      (state, event) ⇒ state.applyEvent(event)
    }
  }

}

