/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import static org.junit.Assert.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Before;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import scala.concurrent.Await;
import scala.concurrent.Future;
import static akka.pattern.Patterns.ask;
import akka.testkit.AkkaSpec;
import akka.testkit.EventFilter;
import akka.testkit.ErrorFilter;
import akka.testkit.TestEvent;
import akka.util.Timeout;

import static akka.japi.Util.immutableSeq;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import scala.collection.JavaConverters;
import scala.collection.immutable.Seq;

public class UntypedTransactorTest {

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("UntypedTransactorTest", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }

  List<ActorRef> counters;
  ActorRef failer;

  int numCounters = 3;
  int timeoutSeconds = 5;

  Timeout timeout = new Timeout(timeoutSeconds, TimeUnit.SECONDS);

  @Before
  public void initialize() {
    counters = new ArrayList<ActorRef>();
    for (int i = 1; i <= numCounters; i++) {
      final String name = "counter" + i;
      ActorRef counter = system.actorOf(new Props(new UntypedActorFactory() {
        private static final long serialVersionUID = 1L;

        public UntypedActor create() {
          return new UntypedCounter(name);
        }
      }));
      counters.add(counter);
    }
    failer = system.actorOf(new Props(UntypedFailer.class));
  }

  @Test
  public void incrementAllCountersWithSuccessfulTransaction() throws Exception {
    CountDownLatch incrementLatch = new CountDownLatch(numCounters);
    Increment message = new Increment(counters.subList(1, counters.size()),
        incrementLatch);
    counters.get(0).tell(message, null);
    try {
      incrementLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
    }
    for (ActorRef counter : counters) {
      Future<Object> future = ask(counter, "GetCount", timeout);
      int count = (Integer) Await.result(future, timeout.duration());
      assertEquals(1, count);
    }
  }

  @Test
  public void incrementNoCountersWithFailingTransaction() throws Exception {
    EventFilter expectedFailureFilter = (EventFilter) new ErrorFilter(
        ExpectedFailureException.class);
    EventFilter coordinatedFilter = (EventFilter) new ErrorFilter(
        CoordinatedTransactionException.class);
    Seq<EventFilter> ignoreExceptions = seq(expectedFailureFilter,
        coordinatedFilter);
    system.eventStream().publish(new TestEvent.Mute(ignoreExceptions));
    CountDownLatch incrementLatch = new CountDownLatch(numCounters);
    List<ActorRef> actors = new ArrayList<ActorRef>(counters);
    actors.add(failer);
    Increment message = new Increment(actors.subList(1, actors.size()),
        incrementLatch);
    actors.get(0).tell(message, null);
    try {
      incrementLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
    }
    for (ActorRef counter : counters) {
      Future<Object> future = ask(counter, "GetCount", timeout);
      int count = (Integer) Await.result(future, timeout.duration());
      assertEquals(0, count);
    }
  }

  public <A> Seq<A> seq(A... args) {
    return immutableSeq(args);
  }
}
