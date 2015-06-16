/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.sharding;

import java.util.concurrent.TimeUnit;
import scala.concurrent.duration.Duration;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.ReceiveTimeout;
import akka.japi.Procedure;
import akka.japi.Option;
import akka.persistence.UntypedPersistentActor;
import akka.cluster.Cluster;
import akka.japi.pf.ReceiveBuilder;

// Doc code, compile only
public class ClusterShardingTest {

  ActorSystem system = null;

  ActorRef getSelf() {
    return null;
  }

  public void demonstrateUsage() {
    //#counter-extractor
    ShardRegion.MessageExtractor messageExtractor = new ShardRegion.MessageExtractor() {

      @Override
      public String entityId(Object message) {
        if (message instanceof Counter.EntityEnvelope)
          return String.valueOf(((Counter.EntityEnvelope) message).id);
        else if (message instanceof Counter.Get)
          return String.valueOf(((Counter.Get) message).counterId);
        else
          return null;
      }

      @Override
      public Object entityMessage(Object message) {
        if (message instanceof Counter.EntityEnvelope)
          return ((Counter.EntityEnvelope) message).payload;
        else
          return message;
      }

      @Override
      public String shardId(Object message) {
        int numberOfShards = 100;
        if (message instanceof Counter.EntityEnvelope) {
          long id = ((Counter.EntityEnvelope) message).id;
          return String.valueOf(id % numberOfShards);
        } else if (message instanceof Counter.Get) {
          long id = ((Counter.Get) message).counterId;
          return String.valueOf(id % numberOfShards);
        } else {
          return null;
        }
      }

    };
    //#counter-extractor

    //#counter-start
    Option<String> roleOption = Option.none();
    ClusterShardingSettings settings = ClusterShardingSettings.create(system);
    ActorRef startedCounterRegion = ClusterSharding.get(system).start("Counter",
      Props.create(Counter.class), settings, messageExtractor);
    //#counter-start

    //#counter-usage
    ActorRef counterRegion = ClusterSharding.get(system).shardRegion("Counter");
    counterRegion.tell(new Counter.Get(123), getSelf());

    counterRegion.tell(new Counter.EntityEnvelope(123,
        Counter.CounterOp.INCREMENT), getSelf());
    counterRegion.tell(new Counter.Get(123), getSelf());
    //#counter-usage
  }

  static//#counter-actor
  public class Counter extends UntypedPersistentActor {

    public static enum CounterOp {
      INCREMENT, DECREMENT
    }

    public static class Get {
      final public long counterId;

      public Get(long counterId) {
        this.counterId = counterId;
      }
    }

    public static class EntityEnvelope {
      final public long id;
      final public Object payload;

      public EntityEnvelope(long id, Object payload) {
        this.id = id;
        this.payload = payload;
      }
    }

    public static class CounterChanged {
      final public int delta;

      public CounterChanged(int delta) {
        this.delta = delta;
      }
    }

    int count = 0;

    // getSelf().path().parent().parent().name() is the type name (utf-8 URL-encoded)
    // getSelf().path().name() is the entity identifier (utf-8 URL-encoded)
    @Override
    public String persistenceId() {
      return getSelf().path().parent().parent().name() + "-" + getSelf().path().name();
    }

    @Override
    public void preStart() throws Exception {
      super.preStart();
      context().setReceiveTimeout(Duration.create(120, TimeUnit.SECONDS));
    }

    void updateState(CounterChanged event) {
      count += event.delta;
    }

    @Override
    public void onReceiveRecover(Object msg) {
      if (msg instanceof CounterChanged)
        updateState((CounterChanged) msg);
      else
        unhandled(msg);
    }

    @Override
    public void onReceiveCommand(Object msg) {
      if (msg instanceof Get)
        getSender().tell(count, getSelf());

      else if (msg == CounterOp.INCREMENT)
        persist(new CounterChanged(+1), new Procedure<CounterChanged>() {
          public void apply(CounterChanged evt) {
            updateState(evt);
          }
        });

      else if (msg == CounterOp.DECREMENT)
        persist(new CounterChanged(-1), new Procedure<CounterChanged>() {
          public void apply(CounterChanged evt) {
            updateState(evt);
          }
        });

      else if (msg.equals(ReceiveTimeout.getInstance()))
        getContext().parent().tell(
            new ShardRegion.Passivate(PoisonPill.getInstance()), getSelf());

      else
        unhandled(msg);
    }
  }

  //#counter-actor

  static//#graceful-shutdown
  public class IllustrateGracefulShutdown extends AbstractActor {

    public IllustrateGracefulShutdown() {
      final ActorSystem system = context().system();
      final Cluster cluster = Cluster.get(system);
      final ActorRef region = ClusterSharding.get(system).shardRegion("Entity");

      receive(ReceiveBuilder.
        match(String.class, s -> s.equals("leave"), s -> {
          context().watch(region);
          region.tell(ShardRegion.gracefulShutdownInstance(), self());
        }).
        match(Terminated.class, t -> t.actor().equals(region), t -> {
          cluster.registerOnMemberRemoved(() -> system.terminate());
          cluster.leave(cluster.selfAddress());
        }).build());
    }
  }
  //#graceful-shutdown

}
