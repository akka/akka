/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class StreamPartialGraphDSLDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamPartialGraphDSLDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }
  
  @Test
  public void demonstrateBuildWithOpenPorts() throws Exception {
    //#simple-partial-graph-dsl
    final Graph<FanInShape2<Integer, Integer, Integer>, NotUsed> zip =
      ZipWith.create((Integer left, Integer right) -> Math.max(left, right));
    
    final Graph<UniformFanInShape<Integer, Integer>, NotUsed> pickMaxOfThree =
        GraphDSL.create(builder -> {
          final FanInShape2<Integer, Integer, Integer> zip1 = builder.add(zip);
          final FanInShape2<Integer, Integer, Integer> zip2 = builder.add(zip);
          
          builder.from(zip1.out()).toInlet(zip2.in0());
          // return the shape, which has three inputs and one output
          return new UniformFanInShape<Integer, Integer>(zip2.out(), 
              new Inlet[] {zip1.in0(), zip1.in1(), zip2.in1()});
        });

    final Sink<Integer, CompletionStage<Integer>> resultSink = Sink.<Integer>head();

    final RunnableGraph<CompletionStage<Integer>> g =
      RunnableGraph.<CompletionStage<Integer>>fromGraph(
        GraphDSL.create(resultSink, (builder, sink) -> {
          // import the partial graph explicitly
          final UniformFanInShape<Integer, Integer> pm = builder.add(pickMaxOfThree);
          
          builder.from(builder.add(Source.single(1))).toInlet(pm.in(0));
          builder.from(builder.add(Source.single(2))).toInlet(pm.in(1));
          builder.from(builder.add(Source.single(3))).toInlet(pm.in(2));
          builder.from(pm.out()).to(sink);
          return ClosedShape.getInstance();
        }));
    
    final CompletionStage<Integer> max = g.run(mat);
    //#simple-partial-graph-dsl
    assertEquals(Integer.valueOf(3), max.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

    //#source-from-partial-graph-dsl
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
  //#source-from-partial-graph-dsl
  
  @Test
  public void demonstrateBuildSourceFromPartialGraphDSLCreate() throws Exception {
    //#source-from-partial-graph-dsl
    final Source<Integer, NotUsed> ints = Source.fromIterator(() -> new Ints());
    
    final Source<Pair<Integer, Integer>, NotUsed> pairs = Source.fromGraph(
      GraphDSL.create(
        builder -> {
          final FanInShape2<Integer, Integer, Pair<Integer, Integer>> zip =
              builder.add(Zip.create());

          builder.from(builder.add(ints.filter(i -> i % 2 == 0))).toInlet(zip.in0());
          builder.from(builder.add(ints.filter(i -> i % 2 == 1))).toInlet(zip.in1());
          
          return SourceShape.of(zip.out());
        }));
    
    final CompletionStage<Pair<Integer, Integer>> firstPair = 
        pairs.runWith(Sink.<Pair<Integer, Integer>>head(), mat);
    //#source-from-partial-graph-dsl
    assertEquals(new Pair<>(0, 1), firstPair.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }
  
  @Test
  public void demonstrateBuildFlowFromPartialGraphDSLCreate() throws Exception {
    //#flow-from-partial-graph-dsl
    final Flow<Integer, Pair<Integer, String>, NotUsed> pairs = Flow.fromGraph(GraphDSL.create(
        b -> {
          final UniformFanOutShape<Integer, Integer> bcast = b.add(Broadcast.create(2));
          final FanInShape2<Integer, String, Pair<Integer, String>> zip =
              b.add(Zip.create());

          b.from(bcast).toInlet(zip.in0());
          b.from(bcast).via(b.add(Flow.of(Integer.class).map(i -> i.toString()))).toInlet(zip.in1());
          
          return FlowShape.of(bcast.in(), zip.out());
        }));
    
    //#flow-from-partial-graph-dsl
    final CompletionStage<Pair<Integer, String>> matSink =
    //#flow-from-partial-graph-dsl
    Source.single(1).via(pairs).runWith(Sink.<Pair<Integer, String>>head(), mat);
    //#flow-from-partial-graph-dsl

    assertEquals(new Pair<>(1, "1"), matSink.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }


  @Test
  public void demonstrateBuildSourceWithCombine() throws Exception {
    //#source-combine
    Source<Integer, NotUsed> source1 = Source.single(1);
    Source<Integer, NotUsed> source2 = Source.single(2);

    final Source<Integer, NotUsed> sources = Source.combine(source1, source2, new ArrayList<>(),
            i -> Merge.<Integer>create(i));
    //#source-combine
    final CompletionStage<Integer> result=
    //#source-combine
    sources.runWith(Sink.<Integer, Integer>fold(0, (a,b) -> a + b), mat);
    //#source-combine

    assertEquals(Integer.valueOf(3), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void demonstrateBuildSinkWithCombine() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    ActorRef actorRef =  probe.getRef();

    //#sink-combine
    Sink<Integer, NotUsed> sendRemotely = Sink.actorRef(actorRef, "Done");
    Sink<Integer, CompletionStage<Done>> localProcessing = Sink.<Integer>foreach(a -> { /*do something useful*/ } );
    Sink<Integer, NotUsed> sinks = Sink.combine(sendRemotely,localProcessing, new ArrayList<>(), a -> Broadcast.create(a));

    Source.<Integer>from(Arrays.asList(new Integer[]{0, 1, 2})).runWith(sinks, mat);
    //#sink-combine
    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
  }
}
