/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.NotUsed;
import akka.stream.ClosedShape;
import akka.stream.SourceShape;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;

public class FlowGraphDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowGraphDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void demonstrateBuildSimpleGraph() throws Exception {
    //#simple-flow-graph
    final Source<Integer, NotUsed> in = Source.from(Arrays.asList(1, 2, 3, 4, 5));
    final Sink<List<String>, CompletionStage<List<String>>> sink = Sink.head();
    final Sink<List<Integer>, CompletionStage<List<Integer>>> sink2 = Sink.head();
    final Flow<Integer, Integer, NotUsed> f1 = Flow.of(Integer.class).map(elem -> elem + 10);
    final Flow<Integer, Integer, NotUsed> f2 = Flow.of(Integer.class).map(elem -> elem + 20);
    final Flow<Integer, String, NotUsed> f3 = Flow.of(Integer.class).map(elem -> elem.toString());
    final Flow<Integer, Integer, NotUsed> f4 = Flow.of(Integer.class).map(elem -> elem + 30);

    final RunnableGraph<CompletionStage<List<String>>> result =
      RunnableGraph.<CompletionStage<List<String>>>fromGraph(
        GraphDSL
          .create(
            sink,
            (builder, out) -> {
              final UniformFanOutShape<Integer, Integer> bcast = builder.add(Broadcast.create(2));
              final UniformFanInShape<Integer, Integer> merge = builder.add(Merge.create(2));

              final Outlet<Integer> source = builder.add(in).out();
              builder.from(source).via(builder.add(f1))
                .viaFanOut(bcast).via(builder.add(f2)).viaFanIn(merge)
                .via(builder.add(f3.grouped(1000))).to(out);
              builder.from(bcast).via(builder.add(f4)).toFanIn(merge);
              return ClosedShape.getInstance();
            }));
    //#simple-flow-graph
    final List<String> list = result.run(mat).toCompletableFuture().get(3, TimeUnit.SECONDS);
    final String[] res = list.toArray(new String[] {});
    Arrays.sort(res, null);
    assertArrayEquals(new String[] { "31", "32", "33", "34", "35", "41", "42", "43", "44", "45" }, res);
  }

  @Test
  @SuppressWarnings("unused")
  public void demonstrateConnectErrors() {
    try {
      //#simple-graph
      final RunnableGraph<NotUsed> g =
        RunnableGraph.<NotUsed>fromGraph(
          GraphDSL
            .create((b) -> {
                final SourceShape<Integer> source1 = b.add(Source.from(Arrays.asList(1, 2, 3, 4, 5)));
                final SourceShape<Integer> source2 = b.add(Source.from(Arrays.asList(1, 2, 3, 4, 5)));
                final FanInShape2<Integer, Integer, Pair<Integer, Integer>> zip = b.add(Zip.create());
                b.from(source1).toInlet(zip.in0());
                b.from(source2).toInlet(zip.in1());
                return ClosedShape.getInstance();
              }
            )
        );
      // unconnected zip.out (!) => "The inlets [] and outlets [] must correspond to the inlets [] and outlets [ZipWith2.out]"
      //#simple-graph
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e != null && e.getMessage() != null && e.getMessage().contains("must correspond to"));
    }
  }

  @Test
  public void demonstrateReusingFlowInGraph() throws Exception {
    //#flow-graph-reusing-a-flow
    final Sink<Integer, CompletionStage<Integer>> topHeadSink = Sink.head();
    final Sink<Integer, CompletionStage<Integer>> bottomHeadSink = Sink.head();
    final Flow<Integer, Integer, NotUsed> sharedDoubler = Flow.of(Integer.class).map(elem -> elem * 2);

    final RunnableGraph<Pair<CompletionStage<Integer>, CompletionStage<Integer>>> g =
      RunnableGraph.<Pair<CompletionStage<Integer>, CompletionStage<Integer>>>fromGraph(
        GraphDSL.create(
          topHeadSink, // import this sink into the graph
          bottomHeadSink, // and this as well
          Keep.both(),
          (b, top, bottom) -> {
            final UniformFanOutShape<Integer, Integer> bcast =
              b.add(Broadcast.create(2));

            b.from(b.add(Source.single(1))).viaFanOut(bcast)
              .via(b.add(sharedDoubler)).to(top);
            b.from(bcast).via(b.add(sharedDoubler)).to(bottom);
            return ClosedShape.getInstance();
          }
        )
      );
    //#flow-graph-reusing-a-flow
    final Pair<CompletionStage<Integer>, CompletionStage<Integer>> pair = g.run(mat);
    assertEquals(Integer.valueOf(2), pair.first().toCompletableFuture().get(3, TimeUnit.SECONDS));
    assertEquals(Integer.valueOf(2), pair.second().toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void demonstrateMatValue() throws Exception {
    //#flow-graph-matvalue
    final Sink<Integer, CompletionStage<Integer>> foldSink = Sink.<Integer, Integer> fold(0, (a, b) -> {
      return a + b;
    });

    final Flow<CompletionStage<Integer>, Integer, NotUsed> flatten =
        Flow.<CompletionStage<Integer>>create().mapAsync(4, x -> x);

    final Flow<Integer, Integer, CompletionStage<Integer>> foldingFlow = Flow.fromGraph(
      GraphDSL.create(foldSink,
      (b, fold) -> {
        return FlowShape.of(
          fold.in(),
          b.from(b.materializedValue()).via(b.add(flatten)).out());
      }));
      //#flow-graph-matvalue

    //#flow-graph-matvalue-cycle
    // This cannot produce any value:
    final Source<Integer, CompletionStage<Integer>> cyclicSource = Source.fromGraph(
      GraphDSL.create(foldSink,
      (b, fold) -> {
        // - Fold cannot complete until its upstream mapAsync completes
        // - mapAsync cannot complete until the materialized Future produced by
        //   fold completes
        // As a result this Source will never emit anything, and its materialited
        // Future will never complete
        b.from(b.materializedValue()).via(b.add(flatten)).to(fold);
        return SourceShape.of(b.from(b.materializedValue()).via(b.add(flatten)).out());
      }));

    //#flow-graph-matvalue-cycle
  }
}
