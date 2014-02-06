/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import static org.junit.Assert.*;

import akka.testkit.*;
import org.junit.*;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;
import scala.concurrent.Await;
import scala.concurrent.Future;
import static akka.pattern.Patterns.ask;

import akka.util.Timeout;

import static akka.japi.Util.immutableSeq;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import scala.collection.JavaConverters;
import scala.collection.immutable.Seq;

public class UntypedTransactorTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("UntypedTransactorTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

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
      ActorRef counter = system.actorOf(Props.create(UntypedCounter.class, name));
      counters.add(counter);
    }
    failer = system.actorOf(Props.create(UntypedFailer.class));
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
