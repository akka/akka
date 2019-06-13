/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.javadsl;

import akka.cluster.ddata.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

// #sample
import java.time.Duration;
import java.util.Optional;
import akka.actor.typed.ActorSystem;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.javadsl.TestKit;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.cluster.ddata.typed.javadsl.Replicator.Command;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;

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

  static final Key<GCounter> KEY = GCounterKey.create("counter");

  static class Counter extends AbstractBehavior<ClientCommand> {
    private final ActorContext<ClientCommand> context;
    private final ActorRef<Replicator.Command> replicator;
    private final SelfUniqueAddress node;
    final ActorRef<Replicator.UpdateResponse<GCounter>> updateResponseAdapter;
    final ActorRef<Replicator.Changed<GCounter>> changedAdapter;
    private final Duration askTimeout = Duration.ofSeconds(10);

    private int cachedValue = 0;

    Counter(ActorContext<ClientCommand> ctx, ActorRef<Command> replicator, SelfUniqueAddress node) {
      context = ctx;
      this.replicator = replicator;
      this.node = node;

      // adapters turning the messages from the replicator
      // into our own protocol
      updateResponseAdapter =
          ctx.messageAdapter(
              // FIXME #27071
              (Class<Replicator.UpdateResponse<GCounter>>) (Object) Replicator.UpdateResponse.class,
              msg -> new InternalUpdateResponse(msg));

      changedAdapter =
          ctx.messageAdapter(
              // FIXME #27071
              (Class<Replicator.Changed<GCounter>>) (Object) Replicator.Changed.class,
              msg -> new InternalChanged(msg));

      replicator.tell(new Replicator.Subscribe<>(KEY, changedAdapter));
    }

    public static Behavior<ClientCommand> create() {
      return Behaviors.setup(
          (ctx) -> {
            SelfUniqueAddress node = DistributedData.get(ctx.getSystem()).selfUniqueAddress();
            ActorRef<Replicator.Command> replicator =
                DistributedData.get(ctx.getSystem()).replicator();

            return new Counter(ctx, replicator, node);
          });
    }

    // #sample
    // omitted from sample, needed for tests, factory above is for the docs sample
    public static Behavior<ClientCommand> create(
        ActorRef<Command> replicator, SelfUniqueAddress node) {
      return Behaviors.setup(ctx -> new Counter(ctx, replicator, node));
    }
    // #sample

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
      replicator.tell(
          new Replicator.Update<>(
              KEY,
              GCounter.empty(),
              Replicator.writeLocal(),
              updateResponseAdapter,
              curr -> curr.increment(node, 1)));
      return Behaviors.same();
    }

    private Behavior<ClientCommand> onGetValue(GetValue cmd) {

      // FIXME #27071
      Class<Replicator.GetResponse<GCounter>> responseClass =
          (Class<Replicator.GetResponse<GCounter>>) (Object) Replicator.GetResponse.class;

      context.ask(
          responseClass,
          replicator,
          askTimeout,
          askReplyTo -> new Replicator.Get<>(KEY, Replicator.readLocal(), askReplyTo),
          (rsp, exc) -> {
            if (exc != null) throw new RuntimeException(exc); // unexpected ask timeout
            return new InternalGetResponse(rsp, cmd.replyTo);
          });

      return Behaviors.same();
    }

    private Behavior<ClientCommand> onGetCachedValue(GetCachedValue cmd) {
      cmd.replyTo.tell(cachedValue);
      return Behaviors.same();
    }

    private Behavior<ClientCommand> onInternalGetResponse(InternalGetResponse msg) {
      if (msg.rsp instanceof Replicator.GetSuccess) {
        int value = ((Replicator.GetSuccess<?>) msg.rsp).get(KEY).getValue().intValue();
        msg.replyTo.tell(value);
        return Behaviors.same();
      } else {
        // not dealing with failures
        return Behaviors.unhandled();
      }
    }

    private Behavior<ClientCommand> onInternalChanged(InternalChanged msg) {
      GCounter counter = msg.chg.get(KEY);
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

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("ReplicatorTest", config);

  private final akka.actor.ActorSystem system = actorSystemResource.getSystem();

  ActorSystem<?> typedSystem() {
    return Adapter.toTyped(system);
  }

  @Test
  public void shouldHaveApiForUpdateAndGet() {
    TestKit probe = new TestKit(system);
    akka.cluster.ddata.ReplicatorSettings settings = ReplicatorSettings.create(typedSystem());
    ActorRef<Replicator.Command> replicator =
        Adapter.spawnAnonymous(system, Replicator.behavior(settings));
    ActorRef<ClientCommand> client =
        Adapter.spawnAnonymous(
            system,
            Counter.create(replicator, DistributedData.get(typedSystem()).selfUniqueAddress()));

    client.tell(Increment.INSTANCE);
    client.tell(new GetValue(Adapter.toTyped(probe.getRef())));
    probe.expectMsg(1);
  }

  @Test
  public void shouldHaveApiForSubscribe() {
    TestKit probe = new TestKit(system);
    akka.cluster.ddata.ReplicatorSettings settings = ReplicatorSettings.create(typedSystem());
    ActorRef<Replicator.Command> replicator =
        Adapter.spawnAnonymous(system, Replicator.behavior(settings));
    ActorRef<ClientCommand> client =
        Adapter.spawnAnonymous(
            system,
            Counter.create(replicator, DistributedData.get(typedSystem()).selfUniqueAddress()));

    client.tell(Increment.INSTANCE);
    client.tell(Increment.INSTANCE);
    probe.awaitAssert(
        () -> {
          client.tell(new GetCachedValue(Adapter.toTyped(probe.getRef())));
          probe.expectMsg(2);
          return null;
        });

    client.tell(Increment.INSTANCE);
    probe.awaitAssert(
        () -> {
          client.tell(new GetCachedValue(Adapter.toTyped(probe.getRef())));
          probe.expectMsg(3);
          return null;
        });
  }
}
