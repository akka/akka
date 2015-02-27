/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.javadsl.FlowGraph.Builder;
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
  
  final FlowMaterializer mat = ActorFlowMaterializer.create(system);
  
  @Test
  public void demonstrateBuildSimpleGraph() throws Exception {
    //#simple-flow-graph
    final Source<Integer, BoxedUnit> in = Source.from(Arrays.asList(1, 2, 3, 4, 5));
    final Sink<List<String>, Future<List<String>>> sink = Sink.head();
    final Flow<Integer, Integer, BoxedUnit> f1 =
        Flow.of(Integer.class).map(elem -> elem + 10);
    final Flow<Integer, Integer, BoxedUnit> f2 =
        Flow.of(Integer.class).map(elem -> elem + 20);
    final Flow<Integer, String, BoxedUnit> f3 =
        Flow.of(Integer.class).map(elem -> elem.toString());
    final Flow<Integer, Integer, BoxedUnit> f4 =
        Flow.of(Integer.class).map(elem -> elem + 30);

    final RunnableFlow<Future<List<String>>> result = FlowGraph.factory()
        .closed(
            sink,
            (builder, out) -> {
              final UniformFanOutShape<Integer, Integer> bcast =
                  builder.graph(Broadcast.create(2));
              final UniformFanInShape<Integer, Integer> merge =
                  builder.graph(Merge.create(2));

              builder.from(in).via(f1).via(bcast).via(f2).via(merge)
                  .via(f3.grouped(1000)).to(out);
              builder.from(bcast).via(f4).to(merge);
            });
    //#simple-flow-graph
    final List<String> list = Await.result(result.run(mat), Duration.create(3, TimeUnit.SECONDS));
    final String[] res = list.toArray(new String[] {});
    Arrays.sort(res, null);
    assertArrayEquals(new String[] {"31", "32", "33", "34", "35", "41", "42", "43", "44", "45"}, res);
  }
  
  @Test
  @SuppressWarnings("unused")
  public void demonstrateConnectErrors() {
    try {
      //#simple-graph
      final Builder b = FlowGraph.builder();
      final Source<Integer, BoxedUnit> source1 = Source.from(Arrays.asList(1, 2, 3, 4, 5));
      final Source<Integer, BoxedUnit> source2 = Source.from(Arrays.asList(1, 2, 3, 4, 5));
      final FanInShape2<Integer, Integer, Pair<Integer, Integer>> zip = b.graph(Zip.create());
      b.edge(b.source(source1), zip.in0());
      b.edge(b.source(source2), zip.in1());
      b.run(mat);
      // unconnected zip.out (!) => "must have at least 1 outgoing edge"
      //#simple-graph
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("unconnected"));  
    }
  }
  
  @Test
  public void demonstrateReusingFlowInGraph() throws Exception {
    //#flow-graph-reusing-a-flow
    final Sink<Integer, Future<Integer>> topHeadSink = Sink.head();
    final Sink<Integer, Future<Integer>> bottomHeadSink = Sink.head();
    final Flow<Integer, Integer, BoxedUnit> sharedDoubler =
        Flow.of(Integer.class).map(elem -> elem * 2);

    final RunnableFlow<Pair<Future<Integer>, Future<Integer>>> g = FlowGraph
        .factory().closed(
            topHeadSink,    // import this sink into the graph
            bottomHeadSink, // and this as well
            Keep.both(),
            (b, top, bottom) -> {
              final UniformFanOutShape<Integer, Integer> bcast = b
                  .graph(Broadcast.create(2));
              
              b.from(Source.single(1)).via(bcast).via(sharedDoubler).to(top);
                                    b.from(bcast).via(sharedDoubler).to(bottom);
            });
    //#flow-graph-reusing-a-flow
    final Pair<Future<Integer>, Future<Integer>> pair = g.run(mat);
    assertEquals(Integer.valueOf(2), Await.result(pair.first(), Duration.create(3, TimeUnit.SECONDS)));
    assertEquals(Integer.valueOf(2), Await.result(pair.second(), Duration.create(3, TimeUnit.SECONDS)));
  }
  
}
