/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern;

import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.ReceiveTimeout;
import akka.japi.Procedure;
import akka.persistence.UntypedPersistentActor;

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
      public String entryId(Object message) {
        if (message instanceof Counter.EntryEnvelope)
          return String.valueOf(((Counter.EntryEnvelope) message).id);
        else if (message instanceof Counter.Get)
          return String.valueOf(((Counter.Get) message).counterId);
        else
          return null;
      }

      @Override
      public Object entryMessage(Object message) {
        if (message instanceof Counter.EntryEnvelope)
          return ((Counter.EntryEnvelope) message).payload;
        else
          return message;
      }

      @Override
      public String shardId(Object message) {
        if (message instanceof Counter.EntryEnvelope) {
          long id = ((Counter.EntryEnvelope) message).id;
          return String.valueOf(id % 10);
        } else if (message instanceof Counter.Get) {
          long id = ((Counter.Get) message).counterId;
          return String.valueOf(id % 10);
        } else {
          return null;
        }
      }

    };
    //#counter-extractor

    //#counter-start
    ActorRef startedCounterRegion = ClusterSharding.get(system).start("Counter", Props.create(Counter.class),
              messageExtractor);
    //#counter-start

    //#counter-usage
    ActorRef counterRegion = ClusterSharding.get(system).shardRegion("Counter");
    counterRegion.tell(new Counter.Get(100), getSelf());

    counterRegion.tell(new Counter.EntryEnvelope(100,
        Counter.CounterOp.INCREMENT), getSelf());
    counterRegion.tell(new Counter.Get(100), getSelf());
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

    public static class EntryEnvelope {
      final public long id;
      final public Object payload;

      public EntryEnvelope(long id, Object payload) {
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
    
    // getSelf().path().parent().name() is the type name (utf-8 URL-encoded) 
    // getSelf().path().name() is the entry identifier (utf-8 URL-encoded)
    @Override
    public String persistenceId() {
      return getSelf().path().parent().name() + "-" + getSelf().path().name();
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

}
