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

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.ActorFlowMaterializerSettings;
import akka.stream.FlowMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.FlowGraph;
import akka.stream.javadsl.OperationAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Zip2With;
import akka.stream.javadsl.ZipWith;
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

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

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
    final FlowMaterializer materializer = ActorFlowMaterializer.create(
      ActorFlowMaterializerSettings.create(system)
        .withInputBuffer(64, 64), system);
    //#materializer-buffer

    //#section-buffer
    final Flow<Integer, Integer> flow =
      Flow.of(Integer.class)
        .section(OperationAttributes.inputBuffer(1, 1), sectionFlow -> 
          // the buffer size of this map is 1
          sectionFlow.<Integer>map(elem -> elem * 2)
        )
        .map(elem -> elem / 2); // the buffer size of this map is the default
    //#section-buffer
  }
  
  @Test
  public void demonstrateBufferAbstractionLeak() {
    //#buffering-abstraction-leak
    final Zip2With<String, Integer, Integer> zipper = 
        ZipWith.create((tick, count) -> count);
    final FiniteDuration oneSecond = 
        FiniteDuration.create(1, TimeUnit.SECONDS);
    final Source<String> msgSource = 
        Source.from(oneSecond, oneSecond, "message!");
    final Source<String> tickSource = 
        Source.from(oneSecond.mul(3), oneSecond.mul(3), "tick");
    final Flow<String, Integer> conflate =
        Flow.of(String.class).conflate(
            first -> 1, (count, elem) -> count + 1); 
    
    FlowGraph.builder()
      .addEdge(msgSource, conflate, zipper.right())
      .addEdge(tickSource, zipper.left())
      .addEdge(zipper.out(), Sink.foreach(elem -> System.out.println(elem)))
      .run(mat);
    //#buffering-abstraction-leak
  }
  
  @Test
  public void demonstrateExplicitBuffers() {
    final Source<Job> inboundJobsConnector = Source.empty();
    //#explicit-buffers-backpressure
    // Getting a stream of jobs from an imaginary external system as a Source
    final Source<Job> jobs = inboundJobsConnector;
    jobs.buffer(1000, OverflowStrategy.backpressure());
    //#explicit-buffers-backpressure

    //#explicit-buffers-droptail
    jobs.buffer(1000, OverflowStrategy.dropTail());
    //#explicit-buffers-droptail

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
