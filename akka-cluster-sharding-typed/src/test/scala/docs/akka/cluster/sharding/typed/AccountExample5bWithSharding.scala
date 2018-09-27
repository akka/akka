/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.scaladsl.ShardedEntity
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehaviors5
import akka.util.Timeout

/*
API experiment with factory for command and event handler
- same as AccountExample5 but with explicit types rather than type alias
- no enclosing class
- nothing special at all, using defs for different command handlers
- using PersistentBehaviors5
- also illustrates how to send commands via Sharding
- and replies
*/

object AccountExample5bWithSharding {
  import AccountExample5b.AccountEntity
  import AccountExample5b.AccountEntity._

  class AccountService(system: ActorSystem[_]) {

    // at system startup
    ClusterSharding(system).start(ShardedEntity(
      entityId ⇒ AccountEntity.behavior(entityId),
      AccountEntity.ShardedEntityTypeKey,
      stopMessage = AccountEntity.Stop // we intend to not require this, https://github.com/akka/akka/issues/25642
    ))

    private def accountEntity(accountNumber: String): EntityRef[AccountCommand] =
      ClusterSharding(system).entityRefFor(AccountEntity.ShardedEntityTypeKey, accountNumber)

    private implicit val askTimout: Timeout = Timeout(5.seconds)

    // illustrate service calls

    def deposit(accountNumber: String, amount: Double): Future[Done] =
      accountEntity(accountNumber) ? Deposit(amount)

    def getBalance(accountNumber: String): Future[Double] =
      accountEntity(accountNumber) ? GetBalance
  }

}

object AccountExample5b {

  object AccountEntity {

    val ShardedEntityTypeKey = EntityTypeKey[AccountCommand]("account")

    sealed trait AccountCommand
    final case class CreateAccount(replyTo: ActorRef[Done]) extends AccountCommand
    final case class Deposit(amount: Double)(val replyTo: ActorRef[Done]) extends AccountCommand
    final case class Withdraw(amount: Double)(val replyTo: ActorRef[Done]) extends AccountCommand
    final case class CloseAccount(replyTo: ActorRef[Done]) extends AccountCommand
    final case class GetBalance(replyTo: ActorRef[Double]) extends AccountCommand
    case object Stop extends AccountCommand // we intend to not require this, https://github.com/akka/akka/issues/25642

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
        case CreateAccount(replyTo) ⇒
          Effect.persist(AccountCreated).thenRun(_ ⇒ replyTo ! Done)
        case Stop ⇒ Effect.stop
        case _    ⇒ Effect.unhandled
      }

    private def openedAccountHandler(acc: OpenedAccount, cmd: AccountCommand): Effect[AccountEvent, Account] = {
      cmd match {
        case cmd @ Deposit(amount) ⇒
          Effect.persist(Deposited(amount)).thenRun(_ ⇒ cmd.replyTo ! Done)

        case cmd @ Withdraw(amount) ⇒
          if ((acc.balance - amount) < 0.0)
            Effect.unhandled // TODO rejection replies are missing in this example
          else {
            Effect
              .persist(Withdrawn(amount))
              .thenRun {
                case OpenedAccount(balance) ⇒
                  // do some side-effect using balance
                  println(balance)
                  cmd.replyTo ! Done
                case _ ⇒ throw new IllegalStateException
              }
          }

        case GetBalance(replyTo) ⇒
          replyTo ! acc.balance
          Effect.none

        case CloseAccount(replyTo) if acc.balance == 0.0 ⇒
          Effect.persist(AccountClosed).thenRun(_ ⇒ replyTo ! Done)

        case CloseAccount(_) ⇒
          Effect.unhandled // TODO rejection replies are missing in this example

        case Stop ⇒ Effect.stop
      }
    }

    private def closedHandler(cmd: AccountCommand): Effect[AccountEvent, Account] =
      cmd match {
        case Stop ⇒ Effect.stop
        case _    ⇒ Effect.unhandled
      }

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

