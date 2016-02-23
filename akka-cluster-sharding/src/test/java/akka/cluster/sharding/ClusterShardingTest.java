/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.sharding;

import static java.util.concurrent.TimeUnit.SECONDS;
import scala.concurrent.duration.Duration;

import akka.actor.AbstractActor;
import akka.actor.ActorInitializationException;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.OneForOneStrategy;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.Terminated;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.japi.Procedure;
import akka.japi.Option;
import akka.persistence.UntypedPersistentActor;
import akka.cluster.Cluster;
import akka.japi.pf.DeciderBuilder;
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

    //#counter-supervisor-start
    ClusterSharding.get(system).start("SupervisedCounter",
        Props.create(CounterSupervisor.class), settings, messageExtractor);
    //#counter-supervisor-start
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

    // getSelf().path().name() is the entity identifier (utf-8 URL-encoded)
    @Override
    public String persistenceId() {
      return "Counter-" + getSelf().path().name();
    }

    @Override
    public void preStart() throws Exception {
      super.preStart();
      context().setReceiveTimeout(Duration.create(120, SECONDS));
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
          cluster.registerOnMemberRemoved(() ->
            self().tell("member-removed", self()));
          cluster.leave(cluster.selfAddress());
        }).
        match(String.class, s -> s.equals("member-removed"), s -> {
          // Let singletons hand over gracefully before stopping the system
          context().system().scheduler().scheduleOnce(Duration.create(10, SECONDS),
              self(), "stop-system", context().dispatcher(), self());
        }).
        match(String.class, s -> s.equals("stop-system"), s -> {
          system.terminate();
        }).
        build());
    }
  }
  //#graceful-shutdown

  static//#supervisor
  public class CounterSupervisor extends UntypedActor {

    private final ActorRef counter = getContext().actorOf(
        Props.create(Counter.class), "theCounter");

    private static final SupervisorStrategy strategy =
      new OneForOneStrategy(DeciderBuilder.
        match(IllegalArgumentException.class, e -> SupervisorStrategy.resume()).
        match(ActorInitializationException.class, e -> SupervisorStrategy.stop()).
        match(Exception.class, e -> SupervisorStrategy.restart()).
        matchAny(o -> SupervisorStrategy.escalate()).build());

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    @Override
    public void onReceive(Object msg) {
      counter.forward(msg, getContext());
    }
  }
  //#supervisor

}
