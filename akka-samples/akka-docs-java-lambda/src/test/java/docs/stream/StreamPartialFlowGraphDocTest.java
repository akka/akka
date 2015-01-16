/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Broadcast;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.FlowGraph;
import akka.stream.javadsl.FlowGraphBuilder;
import akka.stream.javadsl.KeyedSink;
import akka.stream.javadsl.MaterializedMap;
import akka.stream.javadsl.PartialFlowGraph;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.UndefinedSink;
import akka.stream.javadsl.UndefinedSource;
import akka.stream.javadsl.Zip;
import akka.stream.javadsl.Zip2With;
import akka.stream.javadsl.ZipWith;
import akka.testkit.JavaTestKit;

public class StreamPartialFlowGraphDocTest {

  static ActorSystem system;
  

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamPartialFlowGraphDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }
  
  final FlowMaterializer mat = FlowMaterializer.create(system);
  
  @Test
  public void demonstrateBuildWithOpenPorts() throws Exception {
    //#simple-partial-flow-graph
    // defined outside as they will be used by different FlowGraphs
    // 1) first by the PartialFlowGraph to mark its open input and output ports
    // 2) then by the assembling FlowGraph which will attach real sinks and sources to them
    final UndefinedSource<Integer> in1 = UndefinedSource.create();
    final UndefinedSource<Integer> in2 = UndefinedSource.create();
    final UndefinedSource<Integer> in3 = UndefinedSource.create();
    final UndefinedSink<Integer> out = UndefinedSink.create();

    final Zip2With<Integer, Integer, Integer> zip1 = ZipWith.create((a, b) -> Math.max(a, b));
    final Zip2With<Integer, Integer, Integer> zip2 = ZipWith.create((a, b) -> Math.max(a, b));
        
    final PartialFlowGraph pickMaxOfThree = FlowGraph.builder()
      .addEdge(in1, zip1.left())
      .addEdge(in2, zip1.right())
      .addEdge(zip1.out(), zip2.left())
      .addEdge(in3, zip2.right())
      .addEdge(zip2.out(), out)
      .buildPartial();
    //#simple-partial-flow-graph
    
    //#simple-partial-flow-graph

    final KeyedSink<Integer, Future<Integer>> resultSink = Sink.<Integer>head();

    final FlowGraphBuilder b = FlowGraph.builder();
    // import the partial flow graph explicitly
    b.importPartialFlowGraph(pickMaxOfThree);

    b.attachSource(in1, Source.single(1));
    b.attachSource(in2, Source.single(2));
    b.attachSource(in3, Source.single(3));
    b.attachSink(out, resultSink);

    final MaterializedMap materialized = b.build().run(mat);
    final Future<Integer> max = materialized.get(resultSink);
    //#simple-partial-flow-graph
    assertEquals(Integer.valueOf(3), Await.result(max, Duration.create(3, TimeUnit.SECONDS)));

    //#simple-partial-flow-graph-import-shorthand
    final FlowGraphBuilder b2 = FlowGraph.builder(pickMaxOfThree);
    b2.attachSource(in1, Source.single(1));
    b2.attachSource(in2, Source.single(2));
    b2.attachSource(in3, Source.single(3));
    b2.attachSink(out, resultSink);
    //#simple-partial-flow-graph-import-shorthand
    final MaterializedMap materialized2 = b2.run(mat);
    final Future<Integer> max2 = materialized2.get(resultSink);
    assertEquals(Integer.valueOf(3), Await.result(max2, Duration.create(3, TimeUnit.SECONDS)));
  }
  
  @Test
  public void demonstrateBuildSourceFromPartialFlowGraph() throws Exception {
    //#source-from-partial-flow-graph
    // prepare graph elements
    final UndefinedSink<Pair<Integer, Integer>> undefinedSink = UndefinedSink.create();
    final Zip2With<Integer, Integer, Pair<Integer, Integer>> zip = Zip.create();
    final Source<Integer> ints = Source.from(Arrays.asList(1, 2, 3, 4, 5));
    final Source<Pair<Integer, Integer>> pairs = Source.fromGraph(builder -> {
      // connect the graph
      builder
        .addEdge(ints, Flow.of(Integer.class).filter(e -> e % 2 != 0), zip.left())
        .addEdge(ints, Flow.of(Integer.class).filter(e -> e % 2 == 0), zip.right())
        .addEdge(zip.out(), undefinedSink);
      // expose undefinedSink
      return undefinedSink;
    });

    final Future<Pair<Integer, Integer>> firstPair = 
        pairs.runWith(Sink.<Pair<Integer, Integer>>head(), mat);
    //#source-from-partial-flow-graph
    assertEquals(new Pair<>(1, 2), Await.result(firstPair, Duration.create(3, TimeUnit.SECONDS)));
  }
  
  @Test
  public void demonstrateBuildFlowFromPartialFlowGraph() throws Exception {
    //#flow-from-partial-flow-graph
    // prepare graph elements
    final UndefinedSource<Integer> undefinedSource = UndefinedSource.create();
    final UndefinedSink<Pair<Integer, String>> undefinedSink = UndefinedSink.create();
    final Broadcast<Integer> broadcast = Broadcast.create();
    final Zip2With<Integer, String, Pair<Integer, String>> zip = Zip.create();
    final Flow<Integer, Pair<Integer, String>> pairUpWithToString = 
      Flow.create(builder -> {
        // connect the graph
        builder
          .addEdge(undefinedSource, broadcast)
          .addEdge(broadcast, Flow.of(Integer.class).map(e -> e), zip.left())
          .addEdge(broadcast, Flow.of(Integer.class).map(e -> e.toString()), zip.right())
          .addEdge(zip.out(), undefinedSink);

        // expose undefined ports
        return new Pair<>(undefinedSource, undefinedSink);
      });
      
    //#flow-from-partial-flow-graph

    final Future<Pair<Integer, String>> matSink =
    //#flow-from-partial-flow-graph
    Source.single(1).via(pairUpWithToString).runWith(Sink.<Pair<Integer, String>>head(), mat);
    //#flow-from-partial-flow-graph

    assertEquals(new Pair<>(1, "1"), Await.result(matSink, Duration.create(3, TimeUnit.SECONDS)));
  }
}
