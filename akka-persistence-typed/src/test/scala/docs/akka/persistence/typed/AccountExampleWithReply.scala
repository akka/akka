/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.Done
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehaviors7 }
import akka.persistence.typed.scaladsl.PersistentBehaviors7.{ HandlerFactoryWithReply, _ }
import docs.akka.persistence.typed.AccountExampleWithReply.AccountEntity._

object AccountExampleWithReply {

  object AccountEntity {

    val handlers = new HandlerFactoryWithReply[AccountCommand[_], AccountEvent, Account]
    import handlers._

    // ---------------------------------------------------------------
    // responses
    sealed trait Response

    sealed trait Confirmation extends Response

    // write-only confirmation, just confirm that command was successful
    case object Accepted extends Confirmation

    // can be used to reject any command
    case class Rejected(reason: String) extends Confirmation

    sealed trait Query extends Response
    case class CurrentBalance(amount: Double) extends Query
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // commands
    sealed trait AccountCommand[R <: Response] extends CommandWithReply[R]

    case class CreateAccount(replyTo: ActorRef[Confirmation]) extends AccountCommand[Confirmation]

    case class Deposit(amount: Double)(val replyTo: ActorRef[Confirmation]) extends AccountCommand[Confirmation]

    case class Withdraw(amount: Double, replyTo: ActorRef[Confirmation]) extends AccountCommand[Confirmation]

    case class CloseAccount(replyTo: ActorRef[Confirmation]) extends AccountCommand[Confirmation]

    case class GetBalance(replyTo: ActorRef[CurrentBalance]) extends AccountCommand[CurrentBalance]
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // events
    sealed trait AccountEvent

    case object AccountCreated extends AccountEvent

    case class Deposited(amount: Double) extends AccountEvent

    case class Withdrawn(amount: Double) extends AccountEvent

    case object AccountClosed extends AccountEvent
    // ---------------------------------------------------------------

    sealed trait Account {
      def applyCommand: CommandHandler

      def applyEvent: EventHandler
    }

    case object EmptyAccount extends Account {
      val applyCommand = CommandHandler {
        case cmd: CreateAccount ⇒
          WithReply(Effect.persist(AccountCreated))
            .thenReply(cmd) { _ ⇒ Accepted }
      }

      val applyEvent = EventHandler {
        case AccountCreated ⇒ OpenedAccount(0.0)
      }

    }

    case class OpenedAccount(balance: Double) extends Account {

      override val applyCommand = CommandHandler {
        case cmd @ Deposit(amount) ⇒
          WithReply(Effect.persist(Deposited(amount)))
            .thenReply(cmd)(_ ⇒ Accepted)

        case cmd @ Withdraw(amount, _) ⇒
          if ((this.balance - amount) < 0.0)
            WithReply(Effect.none)
              .thenReply(cmd)(_ ⇒ Rejected("not enough balance"))
          else {
            WithReply(Effect.persist(Withdrawn(amount)))
              .thenReply(cmd)(_ ⇒ Accepted)
          }

        case cmd: CloseAccount ⇒
          if (this.balance == 0.0)
            WithReply(Effect.persist(AccountClosed))
              .thenReply(cmd)(_ ⇒ Accepted)
          else
            WithReply(Effect.none)
              .thenReply(cmd)(_ ⇒ Rejected("Account can't be closed because it still have balance"))
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

    def behavior(accountNumber: String): Behavior[AccountEntity.AccountCommand[_]] = {
      PersistentBehaviors7.receive[AccountCommand[_], AccountEvent, Account](
        accountNumber,
        EmptyAccount,
        (state, cmd) ⇒ state.applyCommand(cmd),
        (state, event) ⇒ state.applyEvent(event)
      )
    }
  }

}
