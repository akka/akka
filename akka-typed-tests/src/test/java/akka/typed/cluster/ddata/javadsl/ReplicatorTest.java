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
import akka.typed.javadsl.Actor;
import akka.typed.javadsl.Adapter;

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

  static final Key<GCounter> Key = GCounterKey.create("counter");

  static Behavior<ClientCommand> client(ActorRef<Replicator.Command<?>> replicator, Cluster node) {
    return Actor.deferred(c -> {

      final ActorRef<Replicator.UpdateResponse<GCounter>> updateResponseAdapter =
          c.spawnAdapter(m -> new InternalUpdateResponse<>(m));

      final ActorRef<Replicator.GetResponse<GCounter>> getResponseAdapter =
          c.spawnAdapter(m -> new InternalGetResponse<>(m));

      return Actor.immutable(ClientCommand.class)
        .onMessage(Increment.class, (ctx, cmd) -> {
          replicator.tell(
            new Replicator.Update<GCounter>(Key, GCounter.empty(), Replicator.writeLocal(), updateResponseAdapter,
              curr -> curr.increment(node, 1)));
          return Actor.same();
        })
        .onMessage(InternalUpdateResponse.class, (ctx, msg) -> {
          return Actor.same();
        })
        .onMessage(GetValue.class, (ctx, cmd) -> {
          replicator.tell(
            new Replicator.Get<GCounter>(Key, Replicator.readLocal(), getResponseAdapter, Optional.of(cmd.replyTo)));
          return Actor.same();
        })
        .onMessage(InternalGetResponse.class, (ctx, msg) -> {
          if (msg.rsp instanceof Replicator.GetSuccess) {
            int value = ((Replicator.GetSuccess<?>) msg.rsp).get(Key).getValue().intValue();
            ActorRef<Integer> replyTo = (ActorRef<Integer>) msg.rsp.request().get();
            replyTo.tell(value);
          } else {
            // not dealing with failures
          }
          return Actor.same();
        })
        .build();
    });
}


  static Config config = ConfigFactory.parseString(
    "akka.actor.provider = cluster \n" +
    "akka.remote.netty.tcp.port = 0 \n" +
    "akka.remote.artery.canonical.port = 0 \n");

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("ReplicatorTest",
    config);

  private final ActorSystem system = actorSystemResource.getSystem();

  akka.typed.ActorSystem<?> typedSystem() {
    return Adapter.toTyped(system);
  }



  @Test
  public void apiPrototype() {
    TestKit probe = new TestKit(system);
    akka.cluster.ddata.ReplicatorSettings settings = ReplicatorSettings.apply(typedSystem());
    ActorRef<Replicator.Command<?>> replicator =
        Adapter.spawn(system, Replicator.behavior(settings), "replicator");
    ActorRef<ClientCommand> client =
        Adapter.spawnAnonymous(system, client(replicator, Cluster.get(system)));

    client.tell(new Increment());
    client.tell(new GetValue(Adapter.toTyped(probe.getRef())));
    probe.expectMsg(1);
  }

}
