/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.Done;
import akka.pattern.StatusReply;
import org.scalatestplus.junit.JUnitSuite;

import static jdocs.akka.cluster.sharding.typed.AccountExampleWithEventHandlersInState.AccountEntity;
import static org.junit.Assert.*;

// #test
import java.math.BigDecimal;
import akka.actor.testkit.typed.javadsl.BehaviorTestKit;
import akka.actor.testkit.typed.javadsl.TestInbox;
import akka.persistence.testkit.javadsl.UnpersistentBehavior;
import akka.persistence.testkit.javadsl.PersistenceEffect;
import akka.persistence.typed.PersistenceId;

import org.junit.Test;

public class AccountExampleUnpersistentDocTest
    // #test
    extends JUnitSuite
// #test
{
  @Test
  public void createWithEmptyBalance() {
    UnpersistentBehavior<AccountEntity.Command, AccountEntity.Event, AccountEntity.Account>
        unpersistent = emptyAccount();

    BehaviorTestKit<AccountEntity.Command> testkit = unpersistent.getBehaviorTestKit();
    TestInbox<StatusReply<Done>> replyToInbox = TestInbox.create();
    TestInbox<AccountEntity.CurrentBalance> getBalanceInbox = TestInbox.create();

    testkit.run(new AccountEntity.CreateAccount(replyToInbox.getRef()));

    replyToInbox.expectMessage(StatusReply.ack());
    unpersistent.getEventProbe().expectPersisted(AccountEntity.AccountCreated.INSTANCE);

    // internal state is only exposed by the behavior via responses to messages or if it happens
    //  to snapshot.  This particular behavior never snapshots, so we query within the actor's
    //  protocol
    assertFalse(unpersistent.getSnapshotProbe().hasEffects());

    testkit.run(new AccountEntity.GetBalance(getBalanceInbox.getRef()));

    assertEquals(BigDecimal.ZERO, getBalanceInbox.receiveMessage().balance);
  }

  @Test
  public void handleDepositAndWithdraw() {
    UnpersistentBehavior<AccountEntity.Command, AccountEntity.Event, AccountEntity.Account>
        unpersistent = openedAccount();

    BehaviorTestKit<AccountEntity.Command> testkit = unpersistent.getBehaviorTestKit();
    TestInbox<StatusReply<Done>> replyToInbox = TestInbox.create();
    TestInbox<AccountEntity.CurrentBalance> getBalanceInbox = TestInbox.create();

    testkit.run(new AccountEntity.Deposit(BigDecimal.valueOf(100), replyToInbox.getRef()));

    replyToInbox.expectMessage(StatusReply.ack());
    assertEquals(
        BigDecimal.valueOf(100),
        unpersistent
            .getEventProbe()
            .expectPersistedClass(AccountEntity.Deposited.class)
            .persistedObject()
            .amount);

    testkit.run(new AccountEntity.GetBalance(getBalanceInbox.getRef()));

    assertEquals(BigDecimal.valueOf(100), getBalanceInbox.receiveMessage().balance);

    testkit.run(new AccountEntity.Withdraw(BigDecimal.valueOf(10), replyToInbox.getRef()));

    replyToInbox.expectMessage(StatusReply.ack());
    // can save the persistence effect for in-depth inspection
    PersistenceEffect<AccountEntity.Withdrawn> withdrawEffect =
        unpersistent.getEventProbe().expectPersistedClass(AccountEntity.Withdrawn.class);
    assertEquals(BigDecimal.valueOf(10), withdrawEffect.persistedObject().amount);
    assertEquals(3L, withdrawEffect.sequenceNr());
    assertTrue(withdrawEffect.tags().isEmpty());

    testkit.run(new AccountEntity.GetBalance(getBalanceInbox.getRef()));

    assertEquals(BigDecimal.valueOf(90), getBalanceInbox.receiveMessage().balance);
  }

  @Test
  public void rejectWithdrawOverdraft() {
    UnpersistentBehavior<AccountEntity.Command, AccountEntity.Event, AccountEntity.Account>
        unpersistent = accountWithBalance(BigDecimal.valueOf(100));

    BehaviorTestKit<AccountEntity.Command> testkit = unpersistent.getBehaviorTestKit();
    TestInbox<StatusReply<Done>> replyToInbox = TestInbox.create();

    testkit.run(new AccountEntity.Withdraw(BigDecimal.valueOf(110), replyToInbox.getRef()));
    assertTrue(replyToInbox.receiveMessage().isError());
    assertFalse(unpersistent.getEventProbe().hasEffects());
  }

  // #test
  private UnpersistentBehavior<AccountEntity.Command, AccountEntity.Event, AccountEntity.Account>
      emptyAccount() {
    return
    // #unpersistent-behavior
    UnpersistentBehavior.fromEventSourced(
        AccountEntity.create("1", PersistenceId.of("Account", "1")),
        null, // use the initial state
        0 // initial sequence number
        );
    // #unpersistent-behavior
  }

  private UnpersistentBehavior<AccountEntity.Command, AccountEntity.Event, AccountEntity.Account>
      openedAccount() {
    return
    // #unpersistent-behavior-provided-state
    UnpersistentBehavior.fromEventSourced(
        AccountEntity.create("1", PersistenceId.of("Account", "1")),
        new AccountEntity.EmptyAccount()
            .openedAccount(), // duplicate the event handler for AccountCreated on an EmptyAccount
        1 // assume that CreateAccount was the first command
        );
    // #unpersistent-behavior-provided-state
  }

  private UnpersistentBehavior<AccountEntity.Command, AccountEntity.Event, AccountEntity.Account>
      accountWithBalance(BigDecimal balance) {
    return UnpersistentBehavior.fromEventSourced(
        AccountEntity.create("1", PersistenceId.of("Account", "1")),
        new AccountEntity.OpenedAccount(balance),
        2);
  }
  // #test
}
// #test
