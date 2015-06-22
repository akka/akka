/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.assertEquals;

import akka.actor.*;
import akka.dispatch.Futures;
import akka.testkit.*;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.testkit.*;
import akka.stream.testkit.javadsl.*;
import akka.testkit.TestProbe;
import scala.util.*;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import scala.runtime.BoxedUnit;

public class StreamTestKitDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamTestKitDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void strictCollection() throws Exception {
    //#strict-collection
    final Sink<Integer, Future<Integer>> sinkUnderTest = Flow.of(Integer.class)
      .map(i -> i * 2)
      .to(Sink.fold(0, (agg, next) -> agg + next), Keep.right());

    final Future<Integer> future = Source.from(Arrays.asList(1, 2, 3, 4))
      .runWith(sinkUnderTest, mat);
    final Integer result = Await.result(future, Duration.create(100, TimeUnit.MILLISECONDS));
    assert(result == 20);
    //#strict-collection
  }

  @Test
  public void groupedPartOfInfiniteStream() throws Exception {
    //#grouped-infinite
    final Source<Integer, BoxedUnit> sourceUnderTest = Source.repeat(1)
      .map(i -> i * 2);

    final Future<List<Integer>> future = sourceUnderTest
      .grouped(10)
      .runWith(Sink.head(), mat);
    final List<Integer> result =
      Await.result(future, Duration.create(100, TimeUnit.MILLISECONDS));
    assertEquals(result, Collections.nCopies(10, 2));
    //#grouped-infinite
  }

  @Test
  public void foldedStream() throws Exception {
    //#folded-stream
    final Flow<Integer, Integer, BoxedUnit> flowUnderTest = Flow.of(Integer.class)
      .takeWhile(i -> i < 5);

    final Future<Integer> future = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6))
      .via(flowUnderTest).runWith(Sink.fold(0, (agg, next) -> agg + next), mat);
    final Integer result = Await.result(future, Duration.create(100, TimeUnit.MILLISECONDS));
    assert(result == 10);
    //#folded-stream
  }

  @Test
  public void pipeToTestProbe() throws Exception {
    //#pipeto-testprobe
    final Source<List<Integer>, BoxedUnit> sourceUnderTest = Source
      .from(Arrays.asList(1, 2, 3, 4))
      .grouped(2);

    final TestProbe probe = new TestProbe(system);
    final Future<List<List<Integer>>> future = sourceUnderTest
      .grouped(2)
      .runWith(Sink.head(), mat);
    akka.pattern.Patterns.pipe(future, system.dispatcher()).to(probe.ref());
    probe.expectMsg(Duration.create(100, TimeUnit.MILLISECONDS),
      Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3, 4))
    );
    //#pipeto-testprobe
  }

  public enum Tick { TOCK, COMPLETED };

  @Test
  public void sinkActorRef() throws Exception {
    //#sink-actorref
    final Source<Tick, Cancellable> sourceUnderTest = Source.from(
      FiniteDuration.create(0, TimeUnit.MILLISECONDS),
      FiniteDuration.create(200, TimeUnit.MILLISECONDS),
      Tick.TOCK);

    final TestProbe probe = new TestProbe(system);
    final Cancellable cancellable = sourceUnderTest
      .to(Sink.actorRef(probe.ref(), Tick.COMPLETED)).run(mat);
    probe.expectMsg(Duration.create(1, TimeUnit.SECONDS), Tick.TOCK);
    probe.expectNoMsg(Duration.create(100, TimeUnit.MILLISECONDS));
    probe.expectMsg(Duration.create(200, TimeUnit.MILLISECONDS), Tick.TOCK);
    cancellable.cancel();
    probe.expectMsg(Duration.create(200, TimeUnit.MILLISECONDS), Tick.COMPLETED);
    //#sink-actorref
  }

  @Test
  public void sourceActorRef() throws Exception {
    //#source-actorref
    final Sink<Integer, Future<String>> sinkUnderTest = Flow.of(Integer.class)
      .map(i -> i.toString())
      .to(Sink.fold("", (agg, next) -> agg + next), Keep.right());

    final Pair<ActorRef, Future<String>> refAndFuture =
      Source.<Integer>actorRef(8, OverflowStrategy.fail())
        .to(sinkUnderTest, Keep.both())
        .run(mat);
    final ActorRef ref = refAndFuture.first();
    final Future<String> future = refAndFuture.second();

    ref.tell(1, ActorRef.noSender());
    ref.tell(2, ActorRef.noSender());
    ref.tell(3, ActorRef.noSender());
    ref.tell(new akka.actor.Status.Success("done"), ActorRef.noSender());

    final String result = Await.result(future, Duration.create(100, TimeUnit.MILLISECONDS));
    assertEquals(result, "123");
    //#source-actorref
  }

  @Test
  public void testSinkProbe() {
    //#test-sink-probe
    final Source<Integer, BoxedUnit> sourceUnderTest = Source.from(Arrays.asList(1, 2, 3, 4))
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
    final Sink<Integer, BoxedUnit> sinkUnderTest = Sink.cancelled();

    TestSource.<Integer>probe(system)
      .to(sinkUnderTest, Keep.left())
      .run(mat)
      .expectCancellation();
    //#test-source-probe
  }

  @Test
  public void injectingFailure() throws Exception {
    //#injecting-failure
    final Sink<Integer, Future<Integer>> sinkUnderTest = Sink.head();

    final Pair<TestPublisher.Probe<Integer>, Future<Integer>> probeAndFuture =
      TestSource.<Integer>probe(system)
        .to(sinkUnderTest, Keep.both())
        .run(mat);
    final TestPublisher.Probe<Integer> probe = probeAndFuture.first();
    final Future<Integer> future = probeAndFuture.second();
    probe.sendError(new Exception("boom"));

    Await.ready(future, Duration.create(100, TimeUnit.MILLISECONDS));
    final Throwable exception = ((Failure)future.value().get()).exception();
    assertEquals(exception.getMessage(), "boom");
    //#injecting-failure
  }

  @Test
  public void testSourceAndTestSink() throws Exception {
    //#test-source-and-sink
    final Flow<Integer, Integer, BoxedUnit> flowUnderTest = Flow.of(Integer.class)
      .mapAsyncUnordered(2, sleep -> akka.pattern.Patterns.after(
        Duration.create(10, TimeUnit.MILLISECONDS),
        system.scheduler(),
        system.dispatcher(),
        Futures.successful(sleep)
      ));

    final Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Integer>> pubAndSub =
      TestSource.<Integer>probe(system)
        .via(flowUnderTest)
        .to(TestSink.<Integer>probe(system), Keep.both())
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
