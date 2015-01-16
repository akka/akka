/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import akka.stream.javadsl.KeyedSink;
import akka.stream.javadsl.MaterializedMap;
import akka.stream.javadsl.Merge;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Zip;
import akka.stream.javadsl.Zip2With;
import akka.testkit.JavaTestKit;

public class FlowGraphDocTest {

  static ActorSystem system;
  

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowGraphDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }
  
  final FlowMaterializer mat = FlowMaterializer.create(system);
  
  @Test
  public void demonstrateBuildSimpleGraph() {
    //#simple-flow-graph
    final Source<Integer> in = Source.from(Arrays.asList(1, 2, 3, 4, 5));
    final Sink<String> out = Sink.ignore();
    final Broadcast<Integer> bcast = Broadcast.create();
    final Merge<Integer> merge = Merge.create();
    final Flow<Integer, Integer> f1 = Flow.of(Integer.class).map(elem -> elem + 10);
    final Flow<Integer, Integer> f2 = Flow.of(Integer.class).map(elem -> elem + 20);;
    final Flow<Integer, String> f3 = Flow.of(Integer.class).map(elem -> elem.toString());
    final Flow<Integer, Integer> f4 = Flow.of(Integer.class).map(elem -> elem + 30);;
        
    final FlowGraph g = FlowGraph.builder()
      .addEdge(in, f1, bcast)
      .addEdge(bcast, f2, merge)
      .addEdge(merge, f3, out)
      .addEdge(bcast, f4, merge)
      .build();
    //#simple-flow-graph
    
    g.run(mat);
  }
  
  @Test
  @SuppressWarnings("unused")
  public void demonstrateConnectErrors() {
    try {
      //#simple-graph
      final Source<Integer> source1 = Source.from(Arrays.asList(1, 2, 3, 4, 5));
      final Source<Integer> source2 = Source.from(Arrays.asList(1, 2, 3, 4, 5));
      final Zip2With<Integer, Integer, Pair<Integer, Integer>> zip = Zip.create();
      final FlowGraph g = FlowGraph.builder()
        .addEdge(source1, zip.left())
        .addEdge(source2, zip.right())
        .build();
        // unconnected zip.out (!) => "must have at least 1 outgoing edge"
      
      //#simple-graph
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("must have at least 1 outgoing edge"));  
    }
  }
  
  @Test
  public void demonstrateReusingFlowInGraph() throws Exception {
    //#flow-graph-reusing-a-flow

    final KeyedSink<Integer, Future<Integer>> topHeadSink = Sink.<Integer>head();
    final KeyedSink<Integer, Future<Integer>> bottomHeadSink = Sink.<Integer>head();
    final Flow<Integer, Integer> sharedDoubler = Flow.of(Integer.class).map(elem -> elem * 2);

    final Broadcast<Integer> broadcast = Broadcast.create();
    //#flow-graph-reusing-a-flow
    final FlowGraph g =
    //#flow-graph-reusing-a-flow
    FlowGraph.builder()
      .addEdge(Source.single(1), broadcast)
      .addEdge(broadcast, sharedDoubler, topHeadSink)
      .addEdge(broadcast, sharedDoubler, bottomHeadSink)
      .build();
    //#flow-graph-reusing-a-flow
    final MaterializedMap map = g.run(mat);
    assertEquals(Integer.valueOf(2), Await.result(map.get(topHeadSink), Duration.create(3, TimeUnit.SECONDS)));
    assertEquals(Integer.valueOf(2), Await.result(map.get(bottomHeadSink), Duration.create(3, TimeUnit.SECONDS)));
  }
  
}
