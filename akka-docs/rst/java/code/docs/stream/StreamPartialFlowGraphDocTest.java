/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import akka.actor.*;
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
        GraphDSL.create(builder -> {
          final FanInShape2<Integer, Integer, Integer> zip1 = builder.add(zip);
          final FanInShape2<Integer, Integer, Integer> zip2 = builder.add(zip);
          
          builder.from(zip1.out()).toInlet(zip2.in0());
          // return the shape, which has three inputs and one output
          return new UniformFanInShape<Integer, Integer>(zip2.out(), 
              new Inlet[] {zip1.in0(), zip1.in1(), zip2.in1()});
        });

    final Sink<Integer, Future<Integer>> resultSink = Sink.<Integer>head();

    final RunnableGraph<Future<Integer>> g =
      RunnableGraph.<Future<Integer>>fromGraph(
        GraphDSL.create(resultSink, (builder, sink) -> {
          // import the partial flow graph explicitly
          final UniformFanInShape<Integer, Integer> pm = builder.add(pickMaxOfThree);
          
          builder.from(builder.add(Source.single(1))).toInlet(pm.in(0));
          builder.from(builder.add(Source.single(2))).toInlet(pm.in(1));
          builder.from(builder.add(Source.single(3))).toInlet(pm.in(2));
          builder.from(pm.out()).to(sink);
          return ClosedShape.getInstance();
        }));
    
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
  public void demonstrateBuildSourceFromPartialFlowGraphCreate() throws Exception {
    //#source-from-partial-flow-graph
    final Source<Integer, BoxedUnit> ints = Source.fromIterator(() -> new Ints());
    
    final Source<Pair<Integer, Integer>, BoxedUnit> pairs = Source.fromGraph(
      GraphDSL.create(
        builder -> {
          final FanInShape2<Integer, Integer, Pair<Integer, Integer>> zip =
              builder.add(Zip.create());

          builder.from(builder.add(ints.filter(i -> i % 2 == 0))).toInlet(zip.in0());
          builder.from(builder.add(ints.filter(i -> i % 2 == 1))).toInlet(zip.in1());
          
          return SourceShape.of(zip.out());
        }));
    
    final Future<Pair<Integer, Integer>> firstPair = 
        pairs.runWith(Sink.<Pair<Integer, Integer>>head(), mat);
    //#source-from-partial-flow-graph
    assertEquals(new Pair<>(0, 1), Await.result(firstPair, Duration.create(3, TimeUnit.SECONDS)));
  }
  
  @Test
  public void demonstrateBuildFlowFromPartialFlowGraphCreate() throws Exception {
    //#flow-from-partial-flow-graph
    final Flow<Integer, Pair<Integer, String>, BoxedUnit> pairs = Flow.fromGraph(GraphDSL.create(
        b -> {
          final UniformFanOutShape<Integer, Integer> bcast = b.add(Broadcast.create(2));
          final FanInShape2<Integer, String, Pair<Integer, String>> zip =
              b.add(Zip.create());

          b.from(bcast).toInlet(zip.in0());
          b.from(bcast).via(b.add(Flow.of(Integer.class).map(i -> i.toString()))).toInlet(zip.in1());
          
          return FlowShape.of(bcast.in(), zip.out());
        }));
    
    //#flow-from-partial-flow-graph
    final Future<Pair<Integer, String>> matSink =
    //#flow-from-partial-flow-graph
    Source.single(1).via(pairs).runWith(Sink.<Pair<Integer, String>>head(), mat);
    //#flow-from-partial-flow-graph

    assertEquals(new Pair<>(1, "1"), Await.result(matSink, Duration.create(3, TimeUnit.SECONDS)));
  }


  @Test
  public void demonstrateBuildSourceWithCombine() throws Exception {
    //#source-combine
    Source<Integer, BoxedUnit> source1 = Source.single(1);
    Source<Integer, BoxedUnit> source2 = Source.single(2);

    final Source<Integer, BoxedUnit> sources = Source.combine(source1, source2, new ArrayList<>(),
            i -> Merge.<Integer>create(i));
    //#source-combine
    final Future<Integer> result=
    //#source-combine
    sources.runWith(Sink.<Integer, Integer>fold(0, (a,b) -> a + b), mat);
    //#source-combine

    assertEquals(Integer.valueOf(3), Await.result(result, Duration.create(3, TimeUnit.SECONDS)));
  }

  @Test
  public void demonstrateBuildSinkWithCombine() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    ActorRef actorRef =  probe.getRef();

    //#sink-combine
    Sink<Integer, BoxedUnit> sendRmotely = Sink.actorRef(actorRef, "Done");
    Sink<Integer, Future<BoxedUnit>> localProcessing = Sink.<Integer>foreach(a -> { /*do something useful*/ } );
    Sink<Integer, BoxedUnit> sinks = Sink.combine(sendRmotely,localProcessing, new ArrayList<>(), a -> Broadcast.create(a));

    Source.<Integer>from(Arrays.asList(new Integer[]{0, 1, 2})).runWith(sinks, mat);
    //#sink-combine
    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
  }
}
