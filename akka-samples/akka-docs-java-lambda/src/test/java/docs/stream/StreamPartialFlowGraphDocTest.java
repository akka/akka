/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.Iterator;
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
  
  final Materializer mat = ActorMaterializer.create(system);
  
  @Test
  public void demonstrateBuildWithOpenPorts() throws Exception {
    //#simple-partial-flow-graph
    final Graph<FanInShape2<Integer, Integer, Integer>, BoxedUnit> zip =
      ZipWith.create((Integer left, Integer right) -> Math.max(left, right));
    
    final Graph<UniformFanInShape<Integer, Integer>, BoxedUnit> pickMaxOfThree =
        FlowGraph.factory().partial(builder -> {
          final FanInShape2<Integer, Integer, Integer> zip1 = builder.graph(zip);
          final FanInShape2<Integer, Integer, Integer> zip2 = builder.graph(zip);
          
          builder.edge(zip1.out(), zip2.in0());
          // return the shape, which has three inputs and one output
          return new UniformFanInShape<Integer, Integer>(zip2.out(), 
              new Inlet[] {zip1.in0(), zip1.in1(), zip2.in1()});
        });

    final Sink<Integer, Future<Integer>> resultSink = Sink.<Integer>head();

    final RunnableFlow<Future<Integer>> g = FlowGraph.factory()
        .closed(resultSink, (builder, sink) -> {
          // import the partial flow graph explicitly
          final UniformFanInShape<Integer, Integer> pm = builder.graph(pickMaxOfThree);
          
          builder.from(Source.single(1)).to(pm.in(0));
          builder.from(Source.single(2)).to(pm.in(1));
          builder.from(Source.single(3)).to(pm.in(2));
          builder.from(pm.out()).to(sink);
        });
    
    final Future<Integer> max = g.run(mat);
    //#simple-partial-flow-graph
    assertEquals(Integer.valueOf(3), Await.result(max, Duration.create(3, TimeUnit.SECONDS)));
  }

    //#source-from-partial-flow-graph
    // first create an indefinite source of integer numbers
    class Ints implements Iterator<Integer> {
      private int next = 0;
      @Override
      public boolean hasNext() {
        return true;
      }
      @Override
      public Integer next() {
        return next++;
      }
    }
  //#source-from-partial-flow-graph
  
  @Test
  public void demonstrateBuildSourceFromPartialFlowGraph() throws Exception {
    //#source-from-partial-flow-graph
    final Source<Integer, BoxedUnit> ints = Source.fromIterator(() -> new Ints());
    
    final Source<Pair<Integer, Integer>, BoxedUnit> pairs = Source.factory().create(
        builder -> {
          final FanInShape2<Integer, Integer, Pair<Integer, Integer>> zip =
              builder.graph(Zip.create());

          builder.from(ints.filter(i -> i % 2 == 0)).to(zip.in0());
          builder.from(ints.filter(i -> i % 2 == 1)).to(zip.in1());
          
          return zip.out();
        });
    
    final Future<Pair<Integer, Integer>> firstPair = 
        pairs.runWith(Sink.<Pair<Integer, Integer>>head(), mat);
    //#source-from-partial-flow-graph
    assertEquals(new Pair<>(0, 1), Await.result(firstPair, Duration.create(3, TimeUnit.SECONDS)));
  }
  
  @Test
  public void demonstrateBuildFlowFromPartialFlowGraph() throws Exception {
    //#flow-from-partial-flow-graph
    final Flow<Integer, Pair<Integer, String>, BoxedUnit> pairs = Flow.factory().create(
        b -> {
          final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.create(2));
          final FanInShape2<Integer, String, Pair<Integer, String>> zip =
              b.graph(Zip.create());

          b.from(bcast).to(zip.in0());
          b.from(bcast).via(Flow.of(Integer.class).map(i -> i.toString())).to(zip.in1());
          
          return new Pair<>(bcast.in(), zip.out());
        });
    
    //#flow-from-partial-flow-graph
    final Future<Pair<Integer, String>> matSink =
    //#flow-from-partial-flow-graph
    Source.single(1).via(pairs).runWith(Sink.<Pair<Integer, String>>head(), mat);
    //#flow-from-partial-flow-graph

    assertEquals(new Pair<>(1, "1"), Await.result(matSink, Duration.create(3, TimeUnit.SECONDS)));
  }
}
