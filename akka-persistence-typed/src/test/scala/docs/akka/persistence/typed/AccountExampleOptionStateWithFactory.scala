package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import akka.persistence.typed.scaladsl.PersistentBehaviors6.{HandlerFactory, HandlerFactoryOption}
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors6}
import docs.akka.persistence.typed.AccountExampleOptionStateWithFactory.AccountEntity.{Account, AccountCommand, AccountEvent}

object AccountExampleOptionStateWithFactory {

  object AccountEntity extends HandlerFactoryOption[AccountCommand,  AccountEvent, Account]{

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
      def applyCommand: CommandHandler
      def applyEvent: EventHandler
    }

    case class OpenedAccount(balance: Double) extends Account {

      override val applyCommand = CommandHandler.partial {
        case Deposit(amount) ⇒ Effect.persist(Deposited(amount))

        case Withdraw(amount) ⇒
          if ((this.balance - amount) < 0.0)
            Effect.unhandled // TODO replies are missing in this example
          else {
            Effect
              .persist(Withdrawn(amount))
              .thenRun {
                case Some(OpenedAccount(bal)) ⇒
                  // do some side-effect using balance
                  println(bal)
                case _ ⇒ throw new IllegalStateException
              }
          }
        case CloseAccount if this.balance == 0.0 ⇒
          Effect.persist(AccountClosed)
      }


      override val applyEvent = EventHandler.partial {
        case Deposited(amount) ⇒ copy(balance = balance + amount)
        case Withdrawn(amount) ⇒ copy(balance = balance - amount)
        case AccountClosed     ⇒ ClosedAccount
      }

    }

    case object ClosedAccount extends Account {
      override val applyCommand = CommandHandler.unhandled
      override val applyEvent = EventHandler.unhandled
    }


    val onFirstCommand = CommandHandler.partial {
      case CreateAccount ⇒ Effect.persist(AccountCreated)
    }

    val onFirstEvent = EventHandler.partial {
      case AccountCreated ⇒ OpenedAccount(0.0)
    }

    def behavior(accountNumber: String): Behavior[AccountEntity.AccountCommand] = {
      PersistentBehaviors6.receive[AccountCommand, AccountEvent, Option[Account]](
        accountNumber,
        None,
        (state, cmd) => state match {
          case None => onFirstCommand(cmd)
          case Some(acc) => acc.applyCommand(cmd)
        },
        (state, event) => state match {
          case None => onFirstEvent(event)
          case Some(acc) => acc.applyEvent(event)
        }
      )
    }
  }

}
