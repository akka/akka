/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

// #test
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import akka.actor.testkit.typed.javadsl.LogCapturing;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.persistence.typed.PersistenceId;

// #test

import org.scalatest.junit.JUnitSuite;

import static jdocs.akka.cluster.sharding.typed.AccountExampleWithEventHandlersInState.AccountEntity;

// #test
public class AccountExampleDocTest
    // #test
    extends JUnitSuite
// #test
{

  // #inmem-config
  private static final String inmemConfig =
      "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n";
  // #inmem-config

  // #snapshot-store-config
  private static final String snapshotConfig =
      "akka.persistence.snapshot-store.plugin = \"akka.persistence.snapshot-store.local\" \n"
          + "akka.persistence.snapshot-store.local.dir = \"target/snapshot-"
          + UUID.randomUUID().toString()
          + "\" \n";
  // #snapshot-store-config

  private static final String config = inmemConfig + snapshotConfig;

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  @Test
  public void handleWithdraw() {
    ActorRef<AccountEntity.Command> ref =
        testKit.spawn(AccountEntity.create("1", PersistenceId.of("Account", "1")));
    TestProbe<AccountEntity.OperationResult> probe =
        testKit.createTestProbe(AccountEntity.OperationResult.class);
    ref.tell(new AccountEntity.CreateAccount(probe.getRef()));
    probe.expectMessage(AccountEntity.Confirmed.INSTANCE);
    ref.tell(new AccountEntity.Deposit(BigDecimal.valueOf(100), probe.getRef()));
    probe.expectMessage(AccountEntity.Confirmed.INSTANCE);
    ref.tell(new AccountEntity.Withdraw(BigDecimal.valueOf(10), probe.getRef()));
    probe.expectMessage(AccountEntity.Confirmed.INSTANCE);
  }

  @Test
  public void rejectWithdrawOverdraft() {
    ActorRef<AccountEntity.Command> ref =
        testKit.spawn(AccountEntity.create("2", PersistenceId.of("Account", "2")));
    TestProbe<AccountEntity.OperationResult> probe =
        testKit.createTestProbe(AccountEntity.OperationResult.class);
    ref.tell(new AccountEntity.CreateAccount(probe.getRef()));
    probe.expectMessage(AccountEntity.Confirmed.INSTANCE);
    ref.tell(new AccountEntity.Deposit(BigDecimal.valueOf(100), probe.getRef()));
    probe.expectMessage(AccountEntity.Confirmed.INSTANCE);
    ref.tell(new AccountEntity.Withdraw(BigDecimal.valueOf(110), probe.getRef()));
    probe.expectMessageClass(AccountEntity.Rejected.class);
  }

  @Test
  public void handleGetBalance() {
    ActorRef<AccountEntity.Command> ref =
        testKit.spawn(AccountEntity.create("3", PersistenceId.of("Account", "3")));
    TestProbe<AccountEntity.OperationResult> opProbe =
        testKit.createTestProbe(AccountEntity.OperationResult.class);
    ref.tell(new AccountEntity.CreateAccount(opProbe.getRef()));
    opProbe.expectMessage(AccountEntity.Confirmed.INSTANCE);
    ref.tell(new AccountEntity.Deposit(BigDecimal.valueOf(100), opProbe.getRef()));
    opProbe.expectMessage(AccountEntity.Confirmed.INSTANCE);

    TestProbe<AccountEntity.CurrentBalance> getProbe =
        testKit.createTestProbe(AccountEntity.CurrentBalance.class);
    ref.tell(new AccountEntity.GetBalance(getProbe.getRef()));
    assertEquals(
        BigDecimal.valueOf(100),
        getProbe.expectMessageClass(AccountEntity.CurrentBalance.class).balance);
  }
}
// #test
