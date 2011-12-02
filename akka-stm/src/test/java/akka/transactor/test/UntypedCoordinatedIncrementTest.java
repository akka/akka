package akka.transactor.test;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Before;

import akka.actor.ActorSystem;
import akka.transactor.Coordinated;
import akka.actor.Actors;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;
import akka.testkit.AkkaSpec;
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
  ActorSystem application = ActorSystem.create("UntypedCoordinatedIncrementTest", AkkaSpec.testConf());

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
    Props p = new Props().withCreator(UntypedFailer.class);
    counters = new ArrayList<ActorRef>();
    for (int i = 1; i <= numCounters; i++) {
      final String name = "counter" + i;
      ActorRef counter = application.actorOf(new Props().withCreator(new UntypedActorFactory() {
        public UntypedActor create() {
          return new UntypedCoordinatedCounter(name);
        }
      }));
      counters.add(counter);
    }
    failer = application.actorOf(p);
  }

  @Test
  public void incrementAllCountersWithSuccessfulTransaction() {
    CountDownLatch incrementLatch = new CountDownLatch(numCounters);
    Increment message = new Increment(counters.subList(1, counters.size()), incrementLatch);
    counters.get(0).tell(new Coordinated(message));
    try {
      incrementLatch.await(timeout, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
    }
    for (ActorRef counter : counters) {
      Future future = counter.ask("GetCount", askTimeout);
      assertEquals(1, ((Integer) future.get()).intValue());
    }
  }

  @Test
  public void incrementNoCountersWithFailingTransaction() {
    EventFilter expectedFailureFilter = (EventFilter) new ErrorFilter(ExpectedFailureException.class);
    EventFilter coordinatedFilter = (EventFilter) new ErrorFilter(CoordinatedTransactionException.class);
    Seq<EventFilter> ignoreExceptions = seq(expectedFailureFilter, coordinatedFilter);
    application.eventStream().publish(new TestEvent.Mute(ignoreExceptions));
    CountDownLatch incrementLatch = new CountDownLatch(numCounters);
    List<ActorRef> actors = new ArrayList<ActorRef>(counters);
    actors.add(failer);
    Increment message = new Increment(actors.subList(1, actors.size()), incrementLatch);
    actors.get(0).tell(new Coordinated(message));
    try {
      incrementLatch.await(timeout, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
    }
    for (ActorRef counter : counters) {
      Future future = counter.ask("GetCount", askTimeout);
      assertEquals(0, ((Integer) future.get()).intValue());
    }
  }

  public <A> Seq<A> seq(A... args) {
    return JavaConverters.collectionAsScalaIterableConverter(Arrays.asList(args)).asScala().toSeq();
  }

  @After
  public void stop() {
    application.stop();
  }
}
