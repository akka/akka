/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.javadsl;

// FIXME move to doc package

import akka.actor.testkit.typed.javadsl.LogCapturing;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.cluster.ddata.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

// #sample
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;

import static org.junit.Assert.assertEquals;

// #sample

public class ReplicatorTest extends JUnitSuite {

  // #sample
  interface ClientCommand {}

  enum Increment implements ClientCommand {
    INSTANCE
  }

  static final class GetValue implements ClientCommand {
    final ActorRef<Integer> replyTo;

    GetValue(ActorRef<Integer> replyTo) {
      this.replyTo = replyTo;
    }
  }

  static final class GetCachedValue implements ClientCommand {
    final ActorRef<Integer> replyTo;

    GetCachedValue(ActorRef<Integer> replyTo) {
      this.replyTo = replyTo;
    }
  }

  private interface InternalMsg extends ClientCommand {}

  private static final class InternalUpdateResponse implements InternalMsg {
    final Replicator.UpdateResponse<GCounter> rsp;

    InternalUpdateResponse(Replicator.UpdateResponse<GCounter> rsp) {
      this.rsp = rsp;
    }
  }

  private static final class InternalGetResponse implements InternalMsg {
    final Replicator.GetResponse<GCounter> rsp;
    final ActorRef<Integer> replyTo;

    InternalGetResponse(Replicator.GetResponse<GCounter> rsp, ActorRef<Integer> replyTo) {
      this.rsp = rsp;
      this.replyTo = replyTo;
    }
  }

  private static final class InternalChanged implements InternalMsg {
    final Replicator.Changed<GCounter> chg;

    InternalChanged(Replicator.Changed<GCounter> chg) {
      this.chg = chg;
    }
  }

  static class Counter extends AbstractBehavior<ClientCommand> {
    private final ActorContext<ClientCommand> context;
    // adapter that turns the response messages from the replicator into our own protocol
    private final ReplicatorMessageAdapter<ClientCommand, GCounter> replicatorAdapter;
    private final SelfUniqueAddress node;
    private final Key<GCounter> key;

    private int cachedValue = 0;

    Counter(
        ActorContext<ClientCommand> ctx,
        ReplicatorMessageAdapter<ClientCommand, GCounter> replicatorAdapter,
        Key<GCounter> key) {

      context = ctx;
      this.replicatorAdapter = replicatorAdapter;
      this.key = key;

      node = DistributedData.get(ctx.getSystem()).selfUniqueAddress();

      this.replicatorAdapter.subscribe(this.key, InternalChanged::new);
    }

    public static Behavior<ClientCommand> create(Key<GCounter> key) {
      return Behaviors.setup(
          ctx ->
              DistributedData.withReplicatorMessageAdapter(
                  (ReplicatorMessageAdapter<ClientCommand, GCounter> replicatorAdapter) ->
                      new Counter(ctx, replicatorAdapter, key)));
    }

    @Override
    public Receive<ClientCommand> createReceive() {
      return newReceiveBuilder()
          .onMessage(Increment.class, this::onIncrement)
          .onMessage(InternalUpdateResponse.class, msg -> Behaviors.same())
          .onMessage(GetValue.class, this::onGetValue)
          .onMessage(GetCachedValue.class, this::onGetCachedValue)
          .onMessage(InternalGetResponse.class, this::onInternalGetResponse)
          .onMessage(InternalChanged.class, this::onInternalChanged)
          .build();
    }

    private Behavior<ClientCommand> onIncrement(Increment cmd) {
      replicatorAdapter.askUpdate(
          askReplyTo ->
              new Replicator.Update<>(
                  key,
                  GCounter.empty(),
                  Replicator.writeLocal(),
                  askReplyTo,
                  curr -> curr.increment(node, 1)),
          InternalUpdateResponse::new);

      return Behaviors.same();
    }

    private Behavior<ClientCommand> onGetValue(GetValue cmd) {
      replicatorAdapter.askGet(
          askReplyTo -> new Replicator.Get<>(key, Replicator.readLocal(), askReplyTo),
          rsp -> new InternalGetResponse(rsp, cmd.replyTo));

      return Behaviors.same();
    }

    private Behavior<ClientCommand> onGetCachedValue(GetCachedValue cmd) {
      cmd.replyTo.tell(cachedValue);
      return Behaviors.same();
    }

    private Behavior<ClientCommand> onInternalGetResponse(InternalGetResponse msg) {
      if (msg.rsp instanceof Replicator.GetSuccess) {
        int value = ((Replicator.GetSuccess<?>) msg.rsp).get(key).getValue().intValue();
        msg.replyTo.tell(value);
        return Behaviors.same();
      } else {
        // not dealing with failures
        return Behaviors.unhandled();
      }
    }

    private Behavior<ClientCommand> onInternalChanged(InternalChanged msg) {
      GCounter counter = msg.chg.get(key);
      cachedValue = counter.getValue().intValue();
      return this;
    }
  }

  // #sample

  static Config config =
      ConfigFactory.parseString(
          "akka.actor.provider = cluster \n"
              + "akka.remote.classic.netty.tcp.port = 0 \n"
              + "akka.remote.artery.canonical.port = 0 \n"
              + "akka.remote.artery.canonical.hostname = 127.0.0.1 \n");

  @ClassRule public static TestKitJunitResource testKit = new TestKitJunitResource(config);

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  @Test
  public void shouldHaveApiForUpdateAndGet() {
    TestProbe<Integer> probe = testKit.createTestProbe(Integer.class);
    ActorRef<ClientCommand> client = testKit.spawn(Counter.create(GCounterKey.create("counter1")));

    client.tell(Increment.INSTANCE);
    client.tell(new GetValue(probe.getRef()));
    probe.expectMessage(1);
  }

  @Test
  public void shouldHaveApiForSubscribe() {
    TestProbe<Integer> probe = testKit.createTestProbe(Integer.class);
    ActorRef<ClientCommand> client = testKit.spawn(Counter.create(GCounterKey.create("counter2")));

    client.tell(Increment.INSTANCE);
    client.tell(Increment.INSTANCE);
    probe.awaitAssert(
        () -> {
          client.tell(new GetCachedValue(probe.getRef()));
          probe.expectMessage(2);
          return null;
        });

    client.tell(Increment.INSTANCE);
    probe.awaitAssert(
        () -> {
          client.tell(new GetCachedValue(probe.getRef()));
          probe.expectMessage(3);
          return null;
        });
  }

  @Test
  public void shouldHaveAnExtension() {
    Key<GCounter> key = GCounterKey.create("counter3");
    ActorRef<ClientCommand> client = testKit.spawn(Counter.create(key));

    TestProbe<Integer> probe = testKit.createTestProbe(Integer.class);
    client.tell(Increment.INSTANCE);
    client.tell(new GetValue(probe.getRef()));
    probe.expectMessage(1);

    TestProbe<Replicator.GetResponse<GCounter>> getReplyProbe = testKit.createTestProbe();
    ActorRef<Replicator.Command> replicator = DistributedData.get(testKit.system()).replicator();
    replicator.tell(new Replicator.Get<>(key, Replicator.readLocal(), getReplyProbe.getRef()));
    @SuppressWarnings("unchecked")
    Replicator.GetSuccess<GCounter> rsp =
        getReplyProbe.expectMessageClass(Replicator.GetSuccess.class);
    assertEquals(1, rsp.get(key).getValue().intValue());
  }
}
