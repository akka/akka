package akka.transactor.test;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Before;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import scala.Option;

public class UntypedTransactorTest {
    List<ActorRef> counters;
    ActorRef failer;

    int numCounters = 5;
    int timeout = 5;

    @Before public void initialise() {
        counters = new ArrayList<ActorRef>();
        for (int i = 1; i <= numCounters; i++) {
            final String name = "counter" + i;
            ActorRef counter = UntypedActor.actorOf(new UntypedActorFactory() {
                public UntypedActor create() {
                    return new UntypedCounter(name);
                }
            });
            counter.start();
            counters.add(counter);
        }
        failer = UntypedActor.actorOf(UntypedFailer.class);
        failer.start();
    }

    @Test public void incrementAllCountersWithSuccessfulTransaction() {
        CountDownLatch incrementLatch = new CountDownLatch(numCounters);
        Increment message = new Increment(counters.subList(1, counters.size()), incrementLatch);
        counters.get(0).sendOneWay(message);
        try {
            incrementLatch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {}
        for (ActorRef counter : counters) {
            Future future = counter.sendRequestReplyFuture("GetCount");
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
        CountDownLatch incrementLatch = new CountDownLatch(numCounters);
        List<ActorRef> actors = new ArrayList<ActorRef>(counters);
        actors.add(failer);
        Increment message = new Increment(actors.subList(1, actors.size()), incrementLatch);
        actors.get(0).sendOneWay(message);
        try {
            incrementLatch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {}
        for (ActorRef counter : counters) {
            Future future = counter.sendRequestReplyFuture("GetCount");
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
    }
}


