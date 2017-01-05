/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import akka.NotUsed;
import docs.AbstractJavaTest;
import org.junit.*;
import static org.junit.Assert.assertEquals;

import akka.actor.*;
import akka.testkit.*;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.testkit.*;
import akka.stream.testkit.javadsl.*;
import akka.testkit.TestProbe;
import scala.util.*;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;


public class StreamTestKitDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamTestKitDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void strictCollection() throws Exception {
    //#strict-collection
    final Sink<Integer, CompletionStage<Integer>> sinkUnderTest = Flow.of(Integer.class)
      .map(i -> i * 2)
      .toMat(Sink.fold(0, (agg, next) -> agg + next), Keep.right());

    final CompletionStage<Integer> future = Source.from(Arrays.asList(1, 2, 3, 4))
      .runWith(sinkUnderTest, mat);
    final Integer result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assert(result == 20);
    //#strict-collection
  }

  @Test
  public void groupedPartOfInfiniteStream() throws Exception {
    //#grouped-infinite
    final Source<Integer, NotUsed> sourceUnderTest = Source.repeat(1)
      .map(i -> i * 2);

    final CompletionStage<List<Integer>> future = sourceUnderTest
      .take(10)
      .runWith(Sink.seq(), mat);
    final List<Integer> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(result, Collections.nCopies(10, 2));
    //#grouped-infinite
  }

  @Test
  public void foldedStream() throws Exception {
    //#folded-stream
    final Flow<Integer, Integer, NotUsed> flowUnderTest = Flow.of(Integer.class)
      .takeWhile(i -> i < 5);

    final CompletionStage<Integer> future = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6))
      .via(flowUnderTest).runWith(Sink.fold(0, (agg, next) -> agg + next), mat);
    final Integer result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assert(result == 10);
    //#folded-stream
  }

  @Test
  public void pipeToTestProbe() throws Exception {
    //#pipeto-testprobe
    final Source<List<Integer>, NotUsed> sourceUnderTest = Source
      .from(Arrays.asList(1, 2, 3, 4))
      .grouped(2);

    final TestProbe probe = new TestProbe(system);
    final CompletionStage<List<List<Integer>>> future = sourceUnderTest
      .grouped(2)
      .runWith(Sink.head(), mat);
    akka.pattern.PatternsCS.pipe(future, system.dispatcher()).to(probe.ref());
    probe.expectMsg(Duration.create(3, TimeUnit.SECONDS),
      Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3, 4))
    );
    //#pipeto-testprobe
  }

  public enum Tick { TOCK, COMPLETED };

  @Test
  public void sinkActorRef() throws Exception {
    //#sink-actorref
    final Source<Tick, Cancellable> sourceUnderTest = Source.tick(
      FiniteDuration.create(0, TimeUnit.MILLISECONDS),
      FiniteDuration.create(200, TimeUnit.MILLISECONDS),
      Tick.TOCK);

    final TestProbe probe = new TestProbe(system);
    final Cancellable cancellable = sourceUnderTest
      .to(Sink.actorRef(probe.ref(), Tick.COMPLETED)).run(mat);
    probe.expectMsg(Duration.create(3, TimeUnit.SECONDS), Tick.TOCK);
    probe.expectNoMsg(Duration.create(100, TimeUnit.MILLISECONDS));
    probe.expectMsg(Duration.create(3, TimeUnit.SECONDS), Tick.TOCK);
    cancellable.cancel();
    probe.expectMsg(Duration.create(3, TimeUnit.SECONDS), Tick.COMPLETED);
    //#sink-actorref
  }

  @Test
  public void sourceActorRef() throws Exception {
    //#source-actorref
    final Sink<Integer, CompletionStage<String>> sinkUnderTest = Flow.of(Integer.class)
      .map(i -> i.toString())
      .toMat(Sink.fold("", (agg, next) -> agg + next), Keep.right());

    final Pair<ActorRef, CompletionStage<String>> refAndCompletionStage =
      Source.<Integer>actorRef(8, OverflowStrategy.fail())
        .toMat(sinkUnderTest, Keep.both())
        .run(mat);
    final ActorRef ref = refAndCompletionStage.first();
    final CompletionStage<String> future = refAndCompletionStage.second();

    ref.tell(1, ActorRef.noSender());
    ref.tell(2, ActorRef.noSender());
    ref.tell(3, ActorRef.noSender());
    ref.tell(new akka.actor.Status.Success("done"), ActorRef.noSender());

    final String result = future.toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertEquals(result, "123");
    //#source-actorref
  }

  @Test
  public void testSinkProbe() {
    //#test-sink-probe
    final Source<Integer, NotUsed> sourceUnderTest = Source.from(Arrays.asList(1, 2, 3, 4))
      .filter(elem -> elem % 2 == 0)
      .map(elem -> elem * 2);

    sourceUnderTest
      .runWith(TestSink.probe(system), mat)
      .request(2)
      .expectNext(4, 8)
      .expectComplete();
    //#test-sink-probe
  }

  @Test
  public void testSourceProbe() {
    //#test-source-probe
    final Sink<Integer, NotUsed> sinkUnderTest = Sink.cancelled();

    TestSource.<Integer>probe(system)
      .toMat(sinkUnderTest, Keep.left())
      .run(mat)
      .expectCancellation();
    //#test-source-probe
  }

  @Test
  public void injectingFailure() throws Exception {
    //#injecting-failure
    final Sink<Integer, CompletionStage<Integer>> sinkUnderTest = Sink.head();

    final Pair<TestPublisher.Probe<Integer>, CompletionStage<Integer>> probeAndCompletionStage =
      TestSource.<Integer>probe(system)
        .toMat(sinkUnderTest, Keep.both())
        .run(mat);
    final TestPublisher.Probe<Integer> probe = probeAndCompletionStage.first();
    final CompletionStage<Integer> future = probeAndCompletionStage.second();
    probe.sendError(new Exception("boom"));

    try {
      future.toCompletableFuture().get(3, TimeUnit.SECONDS);
      assert false;
    } catch (ExecutionException ee) {
      final Throwable exception = ee.getCause();
      assertEquals(exception.getMessage(), "boom");
    }
    //#injecting-failure
  }

  @Test
  public void testSourceAndTestSink() throws Exception {
    //#test-source-and-sink
    final Flow<Integer, Integer, NotUsed> flowUnderTest = Flow.of(Integer.class)
      .mapAsyncUnordered(2, sleep -> akka.pattern.PatternsCS.after(
        Duration.create(10, TimeUnit.MILLISECONDS),
        system.scheduler(),
        system.dispatcher(),
        CompletableFuture.completedFuture(sleep)
      ));

    final Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Integer>> pubAndSub =
      TestSource.<Integer>probe(system)
        .via(flowUnderTest)
        .toMat(TestSink.<Integer>probe(system), Keep.both())
        .run(mat);
    final TestPublisher.Probe<Integer> pub = pubAndSub.first();
    final TestSubscriber.Probe<Integer> sub = pubAndSub.second();

    sub.request(3);
    pub.sendNext(3);
    pub.sendNext(2);
    pub.sendNext(1);
    sub.expectNextUnordered(1, 2, 3);

    pub.sendError(new Exception("Power surge in the linear subroutine C-47!"));
    final Throwable ex = sub.expectError();
    assert(ex.getMessage().contains("C-47"));
    //#test-source-and-sink
  }
}
