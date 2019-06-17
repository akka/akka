/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static jdocs.akka.cluster.sharding.typed.AccountExampleWithEventHandlersInState.AccountEntity;
import static jdocs.akka.cluster.sharding.typed.AccountExampleWithEventHandlersInState.AccountEntity.*;
import static org.junit.Assert.assertEquals;

public class AccountExampleTest extends JUnitSuite {

  public static final Config config =
      ConfigFactory.parseString(
          "akka.actor.provider = cluster \n"
              + "akka.remote.classic.netty.tcp.port = 0 \n"
              + "akka.remote.artery.canonical.port = 0 \n"
              + "akka.remote.artery.canonical.hostname = 127.0.0.1 \n"
              + "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n");

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  private ClusterSharding _sharding = null;

  private ClusterSharding sharding() {
    if (_sharding == null) {
      // initialize first time only
      Cluster cluster = Cluster.get(testKit.system());
      cluster.manager().tell(new Join(cluster.selfMember().address()));

      ClusterSharding sharding = ClusterSharding.get(testKit.system());
      sharding.init(
          Entity.ofEventSourcedEntityWithEnforcedReplies(
              AccountEntity.ENTITY_TYPE_KEY, ctx -> AccountEntity.create(ctx.getEntityId())));
      _sharding = sharding;
    }
    return _sharding;
  }

  @Test
  public void handleDeposit() {
    EntityRef<AccountCommand> ref = sharding().entityRefFor(AccountEntity.ENTITY_TYPE_KEY, "1");
    TestProbe<OperationResult> probe = testKit.createTestProbe(OperationResult.class);
    ref.tell(new CreateAccount(probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Deposit(BigDecimal.valueOf(100), probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Deposit(BigDecimal.valueOf(10), probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
  }

  @Test
  public void handleWithdraw() {
    EntityRef<AccountCommand> ref = sharding().entityRefFor(AccountEntity.ENTITY_TYPE_KEY, "2");
    TestProbe<OperationResult> probe = testKit.createTestProbe(OperationResult.class);
    ref.tell(new CreateAccount(probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Deposit(BigDecimal.valueOf(100), probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Withdraw(BigDecimal.valueOf(10), probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
  }

  @Test
  public void rejectWithdrawOverdraft() {
    EntityRef<AccountCommand> ref = sharding().entityRefFor(AccountEntity.ENTITY_TYPE_KEY, "3");
    TestProbe<OperationResult> probe = testKit.createTestProbe(OperationResult.class);
    ref.tell(new CreateAccount(probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Deposit(BigDecimal.valueOf(100), probe.getRef()));
    probe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Withdraw(BigDecimal.valueOf(110), probe.getRef()));
    probe.expectMessageClass(Rejected.class);
  }

  @Test
  public void handleGetBalance() {
    EntityRef<AccountCommand> ref = sharding().entityRefFor(AccountEntity.ENTITY_TYPE_KEY, "4");
    TestProbe<OperationResult> opProbe = testKit.createTestProbe(OperationResult.class);
    ref.tell(new CreateAccount(opProbe.getRef()));
    opProbe.expectMessage(Confirmed.INSTANCE);
    ref.tell(new Deposit(BigDecimal.valueOf(100), opProbe.getRef()));
    opProbe.expectMessage(Confirmed.INSTANCE);

    TestProbe<CurrentBalance> getProbe = testKit.createTestProbe(CurrentBalance.class);
    ref.tell(new GetBalance(getProbe.getRef()));
    assertEquals(
        BigDecimal.valueOf(100), getProbe.expectMessageClass(CurrentBalance.class).balance);
  }

  @Test
  public void beUsableWithAsk() throws Exception {
    Duration timeout = Duration.ofSeconds(3);
    EntityRef<AccountCommand> ref = sharding().entityRefFor(AccountEntity.ENTITY_TYPE_KEY, "5");
    CompletionStage<OperationResult> createResult = ref.ask(CreateAccount::new, timeout);
    assertEquals(Confirmed.INSTANCE, createResult.toCompletableFuture().get(3, TimeUnit.SECONDS));

    // above works because then the response type is inferred by the lhs type
    // below requires (ActorRef<OperationResult> replyTo)

    assertEquals(
        Confirmed.INSTANCE,
        ref.ask(
                (ActorRef<OperationResult> replyTo) ->
                    new Deposit(BigDecimal.valueOf(100), replyTo),
                timeout)
            .toCompletableFuture()
            .get(3, TimeUnit.SECONDS));

    assertEquals(
        Confirmed.INSTANCE,
        ref.ask(
                (ActorRef<OperationResult> replyTo) ->
                    new Withdraw(BigDecimal.valueOf(10), replyTo),
                timeout)
            .toCompletableFuture()
            .get(3, TimeUnit.SECONDS));

    BigDecimal balance =
        ref.ask(GetBalance::new, timeout)
            .thenApply(currentBalance -> currentBalance.balance)
            .toCompletableFuture()
            .get(3, TimeUnit.SECONDS);
    assertEquals(BigDecimal.valueOf(90), balance);
  }
}
