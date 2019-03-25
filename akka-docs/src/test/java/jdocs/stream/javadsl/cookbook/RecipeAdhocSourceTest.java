/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import akka.Done;
import akka.actor.ActorSystem;
import akka.dispatch.Futures;
import akka.japi.pf.PFBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Source;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Promise;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class RecipeAdhocSourceTest extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;
  Duration duration200mills = Duration.ofMillis(200);

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeAdhocSource");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  // #adhoc-source
  public <T> Source<T, ?> adhocSource(Source<T, ?> source, Duration timeout, int maxRetries) {
    return Source.lazily(
        () ->
            source
                .backpressureTimeout(timeout)
                .recoverWithRetries(
                    maxRetries,
                    new PFBuilder()
                        .match(
                            TimeoutException.class,
                            ex -> Source.lazily(() -> source.backpressureTimeout(timeout)))
                        .build()));
  }
  // #adhoc-source

  @Test
  @Ignore
  public void noStart() throws Exception {
    new TestKit(system) {
      {
        AtomicBoolean isStarted = new AtomicBoolean();
        adhocSource(
            Source.empty()
                .mapMaterializedValue(
                    x -> {
                      isStarted.set(true);
                      return x;
                    }),
            duration200mills,
            3);
        Thread.sleep(300);
        assertEquals(false, isStarted.get());
      }
    };
  }

  @Test
  @Ignore
  public void startStream() throws Exception {
    new TestKit(system) {
      {
        TestSubscriber.Probe<String> probe =
            adhocSource(Source.repeat("a"), duration200mills, 3)
                .toMat(TestSink.probe(system), Keep.right())
                .run(mat);
        probe.requestNext("a");
      }
    };
  }

  @Test
  @Ignore
  public void shutdownStream() throws Exception {
    new TestKit(system) {
      {
        Promise<Done> shutdown = Futures.promise();
        TestSubscriber.Probe<String> probe =
            adhocSource(
                    Source.repeat("a")
                        .watchTermination(
                            (a, term) -> term.thenRun(() -> shutdown.success(Done.getInstance()))),
                    duration200mills,
                    3)
                .toMat(TestSink.probe(system), Keep.right())
                .run(mat);

        probe.requestNext("a");
        Thread.sleep(300);
        Await.result(shutdown.future(), duration("3 seconds"));
      }
    };
  }

  @Test
  @Ignore
  public void notShutDownStream() throws Exception {
    new TestKit(system) {
      {
        Promise<Done> shutdown = Futures.promise();
        TestSubscriber.Probe<String> probe =
            adhocSource(
                    Source.repeat("a")
                        .watchTermination(
                            (a, term) -> term.thenRun(() -> shutdown.success(Done.getInstance()))),
                    duration200mills,
                    3)
                .toMat(TestSink.probe(system), Keep.right())
                .run(mat);

        probe.requestNext("a");
        Thread.sleep(100);
        probe.requestNext("a");
        Thread.sleep(100);
        probe.requestNext("a");
        Thread.sleep(100);
        probe.requestNext("a");
        Thread.sleep(100);
        probe.requestNext("a");
        Thread.sleep(100);

        assertEquals(false, shutdown.isCompleted());
      }
    };
  }

  @Test
  @Ignore
  public void restartUponDemand() throws Exception {
    new TestKit(system) {
      {
        Promise<Done> shutdown = Futures.promise();
        AtomicInteger startedCount = new AtomicInteger(0);

        Source<String, ?> source =
            Source.<String>empty()
                .mapMaterializedValue(x -> startedCount.incrementAndGet())
                .concat(Source.repeat("a"));

        TestSubscriber.Probe<String> probe =
            adhocSource(
                    source.watchTermination(
                        (a, term) -> term.thenRun(() -> shutdown.success(Done.getInstance()))),
                    duration200mills,
                    3)
                .toMat(TestSink.probe(system), Keep.right())
                .run(mat);

        probe.requestNext("a");
        assertEquals(1, startedCount.get());
        Thread.sleep(200);
        Await.result(shutdown.future(), duration("3 seconds"));
      }
    };
  }

  @Test
  @Ignore
  public void restartUptoMaxRetries() throws Exception {
    new TestKit(system) {
      {
        Promise<Done> shutdown = Futures.promise();
        AtomicInteger startedCount = new AtomicInteger(0);

        Source<String, ?> source =
            Source.<String>empty()
                .mapMaterializedValue(x -> startedCount.incrementAndGet())
                .concat(Source.repeat("a"));

        TestSubscriber.Probe<String> probe =
            adhocSource(
                    source.watchTermination(
                        (a, term) -> term.thenRun(() -> shutdown.success(Done.getInstance()))),
                    duration200mills,
                    3)
                .toMat(TestSink.probe(system), Keep.right())
                .run(mat);

        probe.requestNext("a");
        assertEquals(1, startedCount.get());

        Thread.sleep(500);
        assertEquals(true, shutdown.isCompleted());

        Thread.sleep(500);
        probe.requestNext("a");
        assertEquals(2, startedCount.get());

        Thread.sleep(500);
        probe.requestNext("a");
        assertEquals(3, startedCount.get());

        Thread.sleep(500);
        probe.requestNext("a");
        assertEquals(4, startedCount.get()); // startCount == 4, which means "re"-tried 3 times

        Thread.sleep(500);
        assertEquals(TimeoutException.class, probe.expectError().getClass());
        probe.request(1); // send demand
        probe.expectNoMessage(FiniteDuration.create(200, "milliseconds")); // but no more restart
      }
    };
  }
}
