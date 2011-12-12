package akka.transactor.test;

import static org.junit.Assert.*;

import akka.dispatch.Await;
import akka.util.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Before;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;
import akka.testkit.EventFilter;
import akka.testkit.ErrorFilter;
import akka.testkit.TestEvent;
import akka.transactor.CoordinatedTransactionException;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import scala.collection.JavaConverters;
import scala.collection.Seq;
import akka.testkit.AkkaSpec;

public class UntypedTransactorTest {

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("UntypedTransactorTest", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    system.stop();
    system = null;
  }

  List<ActorRef> counters;
  ActorRef failer;

  int numCounters = 3;
  int timeout = 5;
  int askTimeout = 5000;

  @Before
  public void initialise() {
    counters = new ArrayList<ActorRef>();
    for (int i = 1; i <= numCounters; i++) {
      final String name = "counter" + i;
      ActorRef counter = system.actorOf(new Props().withCreator(new UntypedActorFactory() {
        public UntypedActor create() {
          return new UntypedCounter(name);
        }
      }));
      counters.add(counter);
    }
    failer = system.actorOf(new Props().withCreator(UntypedFailer.class));
  }

  @Test
  public void incrementAllCountersWithSuccessfulTransaction() {
    CountDownLatch incrementLatch = new CountDownLatch(numCounters);
    Increment message = new Increment(counters.subList(1, counters.size()), incrementLatch);
    counters.get(0).tell(message);
    try {
      incrementLatch.await(timeout, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
    }
    for (ActorRef counter : counters) {
      Future<Object> future = counter.ask("GetCount", askTimeout);
      int count = (Integer) Await.result(future, Duration.create(askTimeout, TimeUnit.MILLISECONDS));
      assertEquals(1, count);
    }
  }

  @Test
  public void incrementNoCountersWithFailingTransaction() {
    EventFilter expectedFailureFilter = (EventFilter) new ErrorFilter(ExpectedFailureException.class);
    EventFilter coordinatedFilter = (EventFilter) new ErrorFilter(CoordinatedTransactionException.class);
    Seq<EventFilter> ignoreExceptions = seq(expectedFailureFilter, coordinatedFilter);
    system.eventStream().publish(new TestEvent.Mute(ignoreExceptions));
    CountDownLatch incrementLatch = new CountDownLatch(numCounters);
    List<ActorRef> actors = new ArrayList<ActorRef>(counters);
    actors.add(failer);
    Increment message = new Increment(actors.subList(1, actors.size()), incrementLatch);
    actors.get(0).tell(message);
    try {
      incrementLatch.await(timeout, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
    }
    for (ActorRef counter : counters) {
      Future<Object> future = counter.ask("GetCount", askTimeout);
      int count = (Integer) Await.result(future, Duration.create(askTimeout, TimeUnit.MILLISECONDS));
      assertEquals(0, count);
    }
  }

  public <A> Seq<A> seq(A... args) {
    return JavaConverters.collectionAsScalaIterableConverter(Arrays.asList(args)).asScala().toSeq();
  }
}
