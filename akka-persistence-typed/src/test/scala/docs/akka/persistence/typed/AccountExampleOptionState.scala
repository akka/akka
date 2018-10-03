package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors6}
import akka.persistence.typed.scaladsl.PersistentBehaviors6.HandlerFactoryOption
import docs.akka.persistence.typed.AccountExampleOptionStateWithFactory.AccountEntity.{CommandHandler, EventHandler}

class AccountExampleOptionState {

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

      type Effect = akka.persistence.typed.scaladsl.Effect[AccountEvent, Option[Account]]

      sealed trait Account {
        def applyCommand(cmd: AccountCommand): Effect
        def applyEvent(event: AccountEvent): Account
      }


      case class OpenedAccount(balance: Double) extends Account {

        override def applyCommand(cmd: AccountCommand): Effect =
          cmd match {
            case Deposit(amount) ⇒ Effect.persist(Deposited(amount))

            case Withdraw(amount) ⇒
              if ((this.balance - amount) < 0.0)
                Effect.unhandled // TODO replies are missing in this example
              else {
                Effect
                  .persist(Withdrawn(amount))
                  .thenRun {
                    case Some(OpenedAccount(0.0)) ⇒
                      // do some side-effect using balance
                      println(balance)
                    case _ ⇒ throw new IllegalStateException
                  }
              }
            case CloseAccount if this.balance == 0.0 ⇒
              Effect.persist(AccountClosed)

            case CloseAccount ⇒
              Effect.unhandled
          }


        override def applyEvent(event: AccountEvent): Account =
          event match {
            case Deposited(amount) ⇒ copy(balance = balance + amount)
            case Withdrawn(amount) ⇒ copy(balance = balance - amount)
            case AccountClosed ⇒ ClosedAccount
            case _ ⇒ throw new IllegalStateException(s"unexpected event [$event] in state [OpenedAccount]")
          }
      }

      case object ClosedAccount extends Account {
        override def applyCommand(cmd: AccountCommand): Effect =
          _ ⇒ Effect.unhandled
        override def applyEvent(event: AccountEvent): Account =
          throw new IllegalStateException(s"unexpected event [$event] in state [ClosedAccount]")
      }

      def onFirstCommand(cmd: AccountCommand): Effect =
        cmd match {
          case CreateAccount ⇒ Effect.persist(AccountCreated)
          case _ ⇒ Effect.unhandled
        }

      def onFirstEvent(event: AccountEvent): Account =
        event match {
          case AccountCreated ⇒ OpenedAccount(0.0)
          case _ ⇒ throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
        }

      def behavior(accountNumber: String): Behavior[AccountEntity.AccountCommand] = {
        PersistentBehaviors6.receive[AccountCommand, AccountEvent, Option[Account]](
          accountNumber,
          None,
          (state, cmd) =>
            state
              .map(_.applyCommand(cmd))
              .getOrElse(onFirstCommand(cmd)),
          (state, event) ⇒
            state
                .map(_.applyEvent(event))
                .orElse(Some(onFirstEvent(event)))
        )
      }

    }
}
