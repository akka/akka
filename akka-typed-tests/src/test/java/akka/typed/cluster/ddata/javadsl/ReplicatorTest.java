/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed.cluster.ddata.javadsl;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.Optional;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import akka.cluster.ddata.GCounter;
import akka.cluster.ddata.GCounterKey;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.ReplicatedData;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.javadsl.TestKit;
import akka.typed.ActorRef;
import akka.typed.Behavior;
import akka.typed.cluster.ddata.javadsl.Replicator.Command;
import akka.typed.javadsl.Actor;
import akka.typed.javadsl.Adapter;
import akka.typed.javadsl.Actor.MutableBehavior;
import akka.typed.javadsl.ActorContext;

public class ReplicatorTest extends JUnitSuite {

  static interface ClientCommand {
  }

  static final class Increment implements ClientCommand {
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

  static interface InternalMsg extends ClientCommand {
  }

  static final class InternalUpdateResponse<A extends ReplicatedData> implements InternalMsg {
    final Replicator.UpdateResponse<A> rsp;

    public InternalUpdateResponse(Replicator.UpdateResponse<A> rsp) {
      this.rsp = rsp;
    }
  }

  static final class InternalGetResponse<A extends ReplicatedData> implements InternalMsg {
    final Replicator.GetResponse<A> rsp;

    public InternalGetResponse(Replicator.GetResponse<A> rsp) {
      this.rsp = rsp;
    }
  }

  static final class InternalChanged<A extends ReplicatedData> implements InternalMsg {
    final Replicator.Changed<A> chg;

    public InternalChanged(Replicator.Changed<A> chg) {
      this.chg = chg;
    }
  }

  static final Key<GCounter> Key = GCounterKey.create("counter");

  static class Client extends MutableBehavior<ClientCommand> {
    private final ActorRef<Replicator.Command> replicator;
    private final Cluster node;
    final ActorRef<Replicator.UpdateResponse<GCounter>> updateResponseAdapter;
    final ActorRef<Replicator.GetResponse<GCounter>> getResponseAdapter;
    final ActorRef<Replicator.Changed<GCounter>> changedAdapter;

    private int cachedValue = 0;

    public Client(ActorRef<Command> replicator, Cluster node, ActorContext<ClientCommand> ctx) {
      this.replicator = replicator;
      this.node = node;

      updateResponseAdapter = ctx.spawnAdapter(m -> new InternalUpdateResponse<>(m));

      getResponseAdapter = ctx.spawnAdapter(m -> new InternalGetResponse<>(m));

      changedAdapter = ctx.spawnAdapter(m -> new InternalChanged<>(m));

      replicator.tell(new Replicator.Subscribe<>(Key, changedAdapter));
    }

    public static Behavior<ClientCommand> create(ActorRef<Command> replicator, Cluster node) {
      return Actor.mutable(ctx -> new Client(replicator, node, ctx));
    }

    @Override
    public Actor.Receive<ClientCommand> createReceive() {
      return receiveBuilder()
        .onMessage(Increment.class, cmd -> {
          replicator.tell(
            new Replicator.Update<GCounter>(Key, GCounter.empty(), Replicator.writeLocal(), updateResponseAdapter,
              curr -> curr.increment(node, 1)));
          return this;
        })
        .onMessage(InternalUpdateResponse.class, msg -> {
          return this;
        })
        .onMessage(GetValue.class, cmd -> {
          replicator.tell(
            new Replicator.Get<GCounter>(Key, Replicator.readLocal(), getResponseAdapter, Optional.of(cmd.replyTo)));
          return this;
        })
        .onMessage(GetCachedValue.class, cmd -> {
          cmd.replyTo.tell(cachedValue);
          return this;
        })
        .onMessage(InternalGetResponse.class, msg -> {
          if (msg.rsp instanceof Replicator.GetSuccess) {
            int value = ((Replicator.GetSuccess<?>) msg.rsp).get(Key).getValue().intValue();
            ActorRef<Integer> replyTo = (ActorRef<Integer>) msg.rsp.request().get();
            replyTo.tell(value);
          } else {
            // not dealing with failures
          }
          return this;
        })
        .onMessage(InternalChanged.class, msg -> {
          GCounter counter = (GCounter) msg.chg.get(Key);
          cachedValue = counter.getValue().intValue();
          return this;
        })
        .build();
    }
}


  static Config config = ConfigFactory.parseString(
    "akka.actor.provider = cluster \n" +
    "akka.remote.netty.tcp.port = 0 \n" +
    "akka.remote.artery.canonical.port = 0 \n" +
    "akka.remote.artery.canonical.hostname = 127.0.0.1 \n");

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("ReplicatorTest",
    config);

  private final ActorSystem system = actorSystemResource.getSystem();

  akka.typed.ActorSystem<?> typedSystem() {
    return Adapter.toTyped(system);
  }



  @Test
  public void shouldHaveApiForUpdateAndGet() {
    TestKit probe = new TestKit(system);
    akka.cluster.ddata.ReplicatorSettings settings = ReplicatorSettings.create(typedSystem());
    ActorRef<Replicator.Command> replicator =
        Adapter.spawnAnonymous(system, Replicator.behavior(settings));
    ActorRef<ClientCommand> client =
        Adapter.spawnAnonymous(system, Client.create(replicator, Cluster.get(system)));

    client.tell(new Increment());
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
        Adapter.spawnAnonymous(system, Client.create(replicator, Cluster.get(system)));

    client.tell(new Increment());
    client.tell(new Increment());
    probe.awaitAssert(() -> {
      client.tell(new GetCachedValue(Adapter.toTyped(probe.getRef())));
      probe.expectMsg(2);
      return null;
    });

    client.tell(new Increment());
    probe.awaitAssert(() -> {
      client.tell(new GetCachedValue(Adapter.toTyped(probe.getRef())));
      probe.expectMsg(3);
      return null;
    });
  }

}
