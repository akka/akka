package akka.transactor.test;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Before;

import akka.AkkaApplication;
import akka.transactor.Coordinated;
import akka.actor.Actors;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;
import akka.event.EventHandler;
import akka.testkit.EventFilter;
import akka.testkit.ErrorFilter;
import akka.testkit.TestEvent;
import akka.transactor.CoordinatedTransactionException;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.Seq;

public class UntypedCoordinatedIncrementTest {
    AkkaApplication application = new AkkaApplication("UntypedCoordinatedIncrementTest");

    List<ActorRef> counters;
    ActorRef failer;

    int numCounters = 5;
    int timeout = 5;
    int askTimeout = 5000;

    @Before public void initialise() {
        counters = new ArrayList<ActorRef>();
        for (int i = 1; i <= numCounters; i++) {
            final String name = "counter" + i;
            ActorRef counter = application.createActor(new Props().withCreator(new UntypedActorFactory() {
                public UntypedActor create() {
                    return new UntypedCoordinatedCounter(name);
                }
            }));
            counters.add(counter);
        }
        failer = application.createActor(new Props().withCreator(UntypedFailer.class));
    }

    @Test public void incrementAllCountersWithSuccessfulTransaction() {
        CountDownLatch incrementLatch = new CountDownLatch(numCounters);
        Increment message = new Increment(counters.subList(1, counters.size()), incrementLatch);
        counters.get(0).tell(new Coordinated(message));
        try {
            incrementLatch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {}
        for (ActorRef counter : counters) {
            Future future = counter.ask("GetCount", askTimeout);
            future.await();
            if (future.isCompleted()) {
                Option resultOption = future.result();
                if (resultOption.isDefined()) {
                    Object result = resultOption.get();
                    int count = (Integer) result;
                    assertEquals(1, count);
                }
            }
        }
    }

    @Test public void incrementNoCountersWithFailingTransaction() {
        EventFilter expectedFailureFilter = (EventFilter) new ErrorFilter(ExpectedFailureException.class);
        EventFilter coordinatedFilter = (EventFilter) new ErrorFilter(CoordinatedTransactionException.class);
        Seq<EventFilter> ignoreExceptions = seq(expectedFailureFilter, coordinatedFilter);
        application.eventHandler().notify(new TestEvent.Mute(ignoreExceptions));
        CountDownLatch incrementLatch = new CountDownLatch(numCounters);
        List<ActorRef> actors = new ArrayList<ActorRef>(counters);
        actors.add(failer);
        Increment message = new Increment(actors.subList(1, actors.size()), incrementLatch);
        actors.get(0).tell(new Coordinated(message));
        try {
            incrementLatch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {}
        for (ActorRef counter : counters) {
            Future future = counter.ask("GetCount", askTimeout);
            future.await();
            if (future.isCompleted()) {
                Option resultOption = future.result();
                if (resultOption.isDefined()) {
                    Object result = resultOption.get();
                    int count = (Integer) result;
                    assertEquals(0, count);
                }
            }
        }
        application.eventHandler().notify(new TestEvent.UnMute(ignoreExceptions));
    }

    public <A> Seq<A> seq(A... args) {
      return JavaConverters.collectionAsScalaIterableConverter(Arrays.asList(args)).asScala().toSeq();
    }
}


