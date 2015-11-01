/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import akka.testkit.TestLatch;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.concurrent.TimeUnit;

public class RecipeMissedTicks extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeMultiGroupBy");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      class Tick {
      }

      final Tick Tick = new Tick();

      {
        final Source<Tick, TestPublisher.Probe<Tick>> tickStream = TestSource.probe(system);
        final Sink<Integer, TestSubscriber.Probe<Integer>> sink = TestSink.probe(system);

        @SuppressWarnings("unused")
        //#missed-ticks
        final Flow<Tick, Integer, BoxedUnit> missedTicks =
          Flow.of(Tick.class).conflate(tick -> 0, (missed, tick) -> missed + 1);
        //#missed-ticks
        final TestLatch latch = new TestLatch(3, system);
        final Flow<Tick, Integer, BoxedUnit> realMissedTicks =
                Flow.of(Tick.class).conflate(tick -> 0, (missed, tick) -> { latch.countDown(); return missed + 1; });

        Pair<TestPublisher.Probe<Tick>, TestSubscriber.Probe<Integer>> pubSub =
        		tickStream.via(realMissedTicks).toMat(sink, Keep.both()).run(mat);
        TestPublisher.Probe<Tick> pub = pubSub.first();
        TestSubscriber.Probe<Integer> sub = pubSub.second();

        pub.sendNext(Tick);
        pub.sendNext(Tick);
        pub.sendNext(Tick);
        pub.sendNext(Tick);

        FiniteDuration timeout = FiniteDuration.create(200, TimeUnit.MILLISECONDS);

        Await.ready(latch, Duration.create(1, TimeUnit.SECONDS));

        sub.request(1);
        sub.expectNext(3);
        sub.request(1);
        sub.expectNoMsg(timeout);

        pub.sendNext(Tick);
        sub.expectNext(0);

        pub.sendComplete();
        sub.request(1);
        sub.expectComplete();

      }
    };
  }

}
