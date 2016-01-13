/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;

public class StreamBuffersRateDocTest {

  static class Job {}

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamBufferRateDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  final SilenceSystemOut.System System = SilenceSystemOut.get();

  @Test
  public void demonstratePipelining() {
    //#pipelining
    Source.from(Arrays.asList(1, 2, 3))
      .map(i -> {System.out.println("A: " + i); return i;})
      .map(i -> {System.out.println("B: " + i); return i;})
      .map(i -> {System.out.println("C: " + i); return i;})
      .runWith(Sink.ignore(), mat);
    //#pipelining
  }

  @Test
  @SuppressWarnings("unused")
  public void demonstrateBufferSizes() {
    //#materializer-buffer
    final Materializer materializer = ActorMaterializer.create(
      ActorMaterializerSettings.create(system)
        .withInputBuffer(64, 64), system);
    //#materializer-buffer

    //#section-buffer
    final Flow<Integer, Integer, BoxedUnit> flow1 =
      Flow.of(Integer.class)
      .map(elem -> elem * 2) // the buffer size of this map is 1
      .withAttributes(Attributes.inputBuffer(1, 1));
    final Flow<Integer, Integer, BoxedUnit> flow2 =
      flow1.via(
        Flow.of(Integer.class)
        .map(elem -> elem / 2)); // the buffer size of this map is the default
    //#section-buffer
  }

  @Test
  public void demonstrateBufferAbstractionLeak() {
    //#buffering-abstraction-leak
    final FiniteDuration oneSecond =
        FiniteDuration.create(1, TimeUnit.SECONDS);
    final Source<String, Cancellable> msgSource =
        Source.tick(oneSecond, oneSecond, "message!");
    final Source<String, Cancellable> tickSource =
        Source.tick(oneSecond.mul(3), oneSecond.mul(3), "tick");
    final Flow<String, Integer, BoxedUnit> conflate =
        Flow.of(String.class).conflate(
            first -> 1, (count, elem) -> count + 1);

    RunnableGraph.fromGraph(GraphDSL.create(b -> {
      final FanInShape2<String, Integer, Integer> zipper =
          b.add(ZipWith.create((String tick, Integer count) -> count));
      b.from(b.add(msgSource)).via(b.add(conflate)).toInlet(zipper.in1());
      b.from(b.add(tickSource)).toInlet(zipper.in0());
      b.from(zipper.out()).to(b.add(Sink.foreach(elem -> System.out.println(elem))));
      return ClosedShape.getInstance();
    })).run(mat);
    //#buffering-abstraction-leak
  }

  @Test
  public void demonstrateExplicitBuffers() {
    final Source<Job, BoxedUnit> inboundJobsConnector = Source.empty();
    //#explicit-buffers-backpressure
    // Getting a stream of jobs from an imaginary external system as a Source
    final Source<Job, BoxedUnit> jobs = inboundJobsConnector;
    jobs.buffer(1000, OverflowStrategy.backpressure());
    //#explicit-buffers-backpressure

    //#explicit-buffers-droptail
    jobs.buffer(1000, OverflowStrategy.dropTail());
    //#explicit-buffers-droptail

    //#explicit-buffers-dropnew
    jobs.buffer(1000, OverflowStrategy.dropNew());
    //#explicit-buffers-dropnew

    //#explicit-buffers-drophead
    jobs.buffer(1000, OverflowStrategy.dropHead());
    //#explicit-buffers-drophead

    //#explicit-buffers-dropbuffer
    jobs.buffer(1000, OverflowStrategy.dropBuffer());
    //#explicit-buffers-dropbuffer

    //#explicit-buffers-fail
    jobs.buffer(1000, OverflowStrategy.fail());
    //#explicit-buffers-fail
  }

}
