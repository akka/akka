/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import akka.Done
import akka.pattern.StatusReply
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

// #test
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.persistence.testkit.scaladsl.UnpersistentBehavior
import akka.persistence.typed.PersistenceId

class AccountExampleUnpersistentDocSpec
    extends AnyWordSpecLike
// #test
    with Matchers
// #test
    {
// #test
  import AccountExampleWithEventHandlersInState.AccountEntity
// #test
  "Account" must {
    "be created with zero balance" in {
      val replyToInbox = TestInbox[StatusReply[Done]]()
      val getBalanceInbox = TestInbox[AccountEntity.CurrentBalance]()

      onAnEmptyAccount { (testkit, eventProbe, snapshotProbe) =>
        testkit.run(AccountEntity.CreateAccount(replyToInbox.ref))
        replyToInbox.expectMessage(StatusReply.Ack)

        eventProbe.expectPersisted(AccountEntity.AccountCreated)

        // internal state is only exposed by the behavior via responses to messages or if it happens
        //  to snapshot.  This particular behavior never snapshots, so we query within the actor's
        //  protocol
        snapshotProbe.hasEffects shouldBe false

        testkit.run(AccountEntity.GetBalance(getBalanceInbox.ref))

        getBalanceInbox.receiveMessage().balance shouldBe 0
      }
    }

    "handle Deposit and Withdraw" in {
      val replyToInbox = TestInbox[StatusReply[Done]]()
      val getBalanceInbox = TestInbox[AccountEntity.CurrentBalance]()

      onAnOpenedAccount { (testkit, eventProbe, _) =>
        testkit.run(AccountEntity.Deposit(100, replyToInbox.ref))

        replyToInbox.expectMessage(StatusReply.Ack)
        eventProbe.expectPersisted(AccountEntity.Deposited(100))

        testkit.run(AccountEntity.Withdraw(10, replyToInbox.ref))

        replyToInbox.expectMessage(StatusReply.Ack)
        eventProbe.expectPersisted(AccountEntity.Withdrawn(10))

        testkit.run(AccountEntity.GetBalance(getBalanceInbox.ref))

        getBalanceInbox.receiveMessage().balance shouldBe 90
      }
    }

    "reject Withdraw overdraft" in {
      val replyToInbox = TestInbox[StatusReply[Done]]()

      onAnAccountWithBalance(100) { (testkit, eventProbe, _) =>
        testkit.run(AccountEntity.Withdraw(110, replyToInbox.ref))

        replyToInbox.receiveMessage().isError shouldBe true
        eventProbe.hasEffects shouldBe false
      }
    }
  }
// #test

  // #unpersistent-behavior
  private def onAnEmptyAccount
      : UnpersistentBehavior.EventSourced[AccountEntity.Command, AccountEntity.Event, AccountEntity.Account] =
    UnpersistentBehavior.fromEventSourced(AccountEntity("1", PersistenceId("Account", "1")))
  // #unpersistent-behavior

  // #unpersistent-behavior-provided-state
  private def onAnOpenedAccount
      : UnpersistentBehavior.EventSourced[AccountEntity.Command, AccountEntity.Event, AccountEntity.Account] =
    UnpersistentBehavior.fromEventSourced(
      AccountEntity("1", PersistenceId("Account", "1")),
      Some(
        AccountEntity.EmptyAccount.applyEvent(AccountEntity.AccountCreated) -> // reuse the event handler
        1L // assume that CreateAccount was the first command
      ))
  // #unpersistent-behavior-provided-state

  private def onAnAccountWithBalance(balance: BigDecimal) =
    UnpersistentBehavior.fromEventSourced(
      AccountEntity("1", PersistenceId("Account", "1")),
      Some(AccountEntity.OpenedAccount(balance) -> 2L))
// #test
}
// #test
