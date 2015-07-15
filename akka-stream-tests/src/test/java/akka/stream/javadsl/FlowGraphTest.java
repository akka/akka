/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.dispatch.japi;
import akka.japi.Pair;
import akka.pattern.Patterns;
import akka.japi.tuple.Tuple4;
import akka.stream.*;
import akka.stream.javadsl.FlowGraph.Builder;
import akka.stream.stage.*;
import akka.japi.function.*;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;

public class FlowGraphTest extends StreamTest {
  public FlowGraphTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowGraphTest",
    AkkaSpec.testConf());

  @SuppressWarnings("serial")
  public <T> Creator<Stage<T, T>> op() {
    return new akka.japi.function.Creator<Stage<T, T>>() {
      @Override
      public PushPullStage<T, T> create() throws Exception {
        return new PushPullStage<T, T>() {
          @Override
          public SyncDirective onPush(T element, Context<T> ctx) {
            return ctx.push(element);
          }

          @Override
          public SyncDirective onPull(Context<T> ctx) {
            return ctx.pull();
          }
        };
      }
    };
  }

  @Test
  public void mustBeAbleToUseMerge() throws Exception {
    final Flow<String, String, BoxedUnit> f1 =
        Flow.of(String.class).transform(FlowGraphTest.this.<String> op()).named("f1");
    final Flow<String, String, BoxedUnit> f2 =
        Flow.of(String.class).transform(FlowGraphTest.this.<String> op()).named("f2");
    @SuppressWarnings("unused")
    final Flow<String, String, BoxedUnit> f3 = 
        Flow.of(String.class).transform(FlowGraphTest.this.<String> op()).named("f3");

    final Source<String, BoxedUnit> in1 = Source.from(Arrays.asList("a", "b", "c"));
    final Source<String, BoxedUnit> in2 = Source.from(Arrays.asList("d", "e", "f"));

    final Sink<String, Publisher<String>> publisher = Sink.publisher();
    
    final Source<String, BoxedUnit> source = Source.factory().create(new Function<FlowGraph.Builder<BoxedUnit>, Outlet<String>>() {
      @Override
      public Outlet<String> apply(Builder<BoxedUnit> b) throws Exception {
        final UniformFanInShape<String, String> merge = b.graph(Merge.<String> create(2));
        b.flow(b.source(in1), f1, merge.in(0));
        b.flow(b.source(in2), f2, merge.in(1));
        return merge.out();
      }
    });

    // collecting
    final Publisher<String> pub = source.runWith(publisher, materializer);
    final Future<List<String>> all = Source.from(pub).grouped(100).runWith(Sink.<List<String>>head(), materializer);

    final List<String> result = Await.result(all, Duration.apply(200, TimeUnit.MILLISECONDS));
    assertEquals(new HashSet<Object>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<String>(result));
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> input2 = Arrays.asList(1, 2, 3);

    final Builder<BoxedUnit> b = FlowGraph.builder();
    final Source<String, BoxedUnit> in1 = Source.from(input1);
    final Source<Integer, BoxedUnit> in2 = Source.from(input2);
    final FanInShape2<String, Integer, Pair<String,Integer>> zip = b.graph(Zip.<String, Integer>create());
    final Sink<Pair<String, Integer>, BoxedUnit> out = createSink(probe);

    b.edge(b.source(in1), zip.in0());
    b.edge(b.source(in2), zip.in1());
    b.edge(zip.out(), b.sink(out));
    b.run(materializer);

    List<Object> output = Arrays.asList(probe.receiveN(3));
    @SuppressWarnings("unchecked")
    List<Pair<String, Integer>> expected = Arrays.asList(new Pair<String, Integer>("A", 1), new Pair<String, Integer>(
            "B", 2), new Pair<String, Integer>("C", 3));
    assertEquals(expected, output);
  }

  @Test
  public void mustBeAbleToUseUnzip() {
    final JavaTestKit probe1 = new JavaTestKit(system);
    final JavaTestKit probe2 = new JavaTestKit(system);

    @SuppressWarnings("unchecked")
    final List<Pair<String, Integer>> input = Arrays.asList(new Pair<String, Integer>("A", 1),
        new Pair<String, Integer>("B", 2), new Pair<String, Integer>("C", 3));

    final Iterable<String> expected1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> expected2 = Arrays.asList(1, 2, 3);

    final Builder<BoxedUnit> b = FlowGraph.builder();
    final Outlet<Pair<String, Integer>> in = b.source(Source.from(input));
    final FanOutShape2<Pair<String, Integer>, String, Integer> unzip = b.graph(Unzip.<String, Integer>create());

    final Sink<String, BoxedUnit> out1 = createSink(probe1);
    final Sink<Integer, BoxedUnit> out2 = createSink(probe2);

    b.edge(in, unzip.in());
    b.edge(unzip.out0(), b.sink(out1));
    b.edge(unzip.out1(), b.sink(out2));
    b.run(materializer);

    List<Object> output1 = Arrays.asList(probe1.receiveN(3));
    List<Object> output2 = Arrays.asList(probe2.receiveN(3));
    assertEquals(expected1, output1);
    assertEquals(expected2, output2);
  }

  private static <T> Sink<T, BoxedUnit> createSink(final JavaTestKit probe){
    return Sink.actorRef(probe.getRef(), "onComplete");
  }

  @Test
  public void mustBeAbleToUseUnzipWith() throws Exception {
    final JavaTestKit probe1 = new JavaTestKit(system);
    final JavaTestKit probe2 = new JavaTestKit(system);

    final Builder<BoxedUnit> b = FlowGraph.builder();
    final Source<Integer, BoxedUnit> in = Source.single(1);

    final FanOutShape2<Integer, String, Integer> unzip = b.graph(UnzipWith.create(
      new Function<Integer, Pair<String, Integer>>() {
        @Override public Pair<String, Integer> apply(Integer l) throws Exception {
          return new Pair<String, Integer>(l + "!", l);
        }
      })
    );

    final Sink<String, BoxedUnit> out1 = createSink(probe1);
    final Sink<Integer, BoxedUnit> out2 = createSink(probe2);

    b.edge(b.source(in), unzip.in());
    b.edge(unzip.out0(), b.sink(out1));
    b.edge(unzip.out1(), b.sink(out2));
    b.run(materializer);

    Duration d = Duration.create(300, TimeUnit.MILLISECONDS);

    Object output1 = probe1.receiveOne(d);
    Object output2 = probe2.receiveOne(d);

    assertEquals("1!", output1);
    assertEquals(1, output2);

  }

  @Test
  public void mustBeAbleToUseUnzip4With() throws Exception {
    final JavaTestKit probe1 = new JavaTestKit(system);
    final JavaTestKit probe2 = new JavaTestKit(system);
    final JavaTestKit probe3 = new JavaTestKit(system);
    final JavaTestKit probe4 = new JavaTestKit(system);

    final Builder<BoxedUnit> b = FlowGraph.builder();
    final Source<Integer, BoxedUnit> in = Source.single(1);

    final FanOutShape4<Integer, String, Integer, String, Integer> unzip = b.graph(UnzipWith.create4(
      new Function<Integer, Tuple4<String, Integer, String, Integer>>() {
        @Override public Tuple4<String, Integer, String, Integer> apply(Integer l) throws Exception {
          return new Tuple4<String, Integer, String, Integer>(l.toString(), l, l + "+" + l, l + l);
        }
      })
    );

    final Sink<String, BoxedUnit> out1 = createSink(probe1);
    final Sink<Integer, BoxedUnit> out2 = createSink(probe2);
    final Sink<String, BoxedUnit> out3 = createSink(probe3);
    final Sink<Integer, BoxedUnit> out4 = createSink(probe4);

    b.edge(b.source(in), unzip.in());
    b.edge(unzip.out0(), b.sink(out1));
    b.edge(unzip.out1(), b.sink(out2));
    b.edge(unzip.out2(), b.sink(out3));
    b.edge(unzip.out3(), b.sink(out4));
    b.run(materializer);

    Duration d = Duration.create(300, TimeUnit.MILLISECONDS);

    Object output1 = probe1.receiveOne(d);
    Object output2 = probe2.receiveOne(d);
    Object output3 = probe3.receiveOne(d);
    Object output4 = probe4.receiveOne(d);

    assertEquals("1", output1);
    assertEquals(1, output2);
    assertEquals("1+1", output3);
    assertEquals(2, output4);
  }

  @Test
  public void mustBeAbleToUseZipWith() throws Exception {
    final Source<Integer, BoxedUnit> in1 = Source.single(1);
    final Source<Integer, BoxedUnit> in2 = Source.single(10);

    final Graph<FanInShape2<Integer, Integer, Integer>, BoxedUnit> sumZip = ZipWith.create(
      new Function2<Integer, Integer, Integer>() {
        @Override public Integer apply(Integer l, Integer r) throws Exception {
          return l + r;
      }
    });
    
    final Future<Integer> future = FlowGraph.factory().closed(Sink.<Integer> head(), new Procedure2<Builder<Future<Integer> >, SinkShape<Integer>>() {
      @Override
      public void apply(Builder<Future<Integer> > b, SinkShape<Integer> out) throws Exception {
        final FanInShape2<Integer, Integer, Integer> zip = b.graph(sumZip);
        b.edge(b.source(in1), zip.in0());
        b.edge(b.source(in2), zip.in1());
        b.edge(zip.out(), out.inlet());
      }
    }).run(materializer);

    final Integer result = Await.result(future, Duration.create(300, TimeUnit.MILLISECONDS));
    assertEquals(11, (int) result);
  }

  @Test
     public void mustBeAbleToUseZip4With() throws Exception {
    final Source<Integer, BoxedUnit> in1 = Source.single(1);
    final Source<Integer, BoxedUnit> in2 = Source.single(10);
    final Source<Integer, BoxedUnit> in3 = Source.single(100);
    final Source<Integer, BoxedUnit> in4 = Source.single(1000);

    final Graph<FanInShape4<Integer, Integer, Integer, Integer, Integer>, BoxedUnit> sumZip = ZipWith.create4(
            new Function4<Integer, Integer, Integer, Integer, Integer>() {
              @Override public Integer apply(Integer i1, Integer i2, Integer i3, Integer i4) throws Exception {
                return i1 + i2 + i3 + i4;
              }
            });

    final Future<Integer> future = FlowGraph.factory().closed(Sink.<Integer> head(), new Procedure2<Builder<Future<Integer>>, SinkShape<Integer>>() {
      @Override
      public void apply(Builder<Future<Integer>> b, SinkShape<Integer> out) throws Exception {
        final FanInShape4<Integer, Integer, Integer, Integer, Integer> zip = b.graph(sumZip);
        b.edge(b.source(in1), zip.in0());
        b.edge(b.source(in2), zip.in1());
        b.edge(b.source(in3), zip.in2());
        b.edge(b.source(in4), zip.in3());
        b.edge(zip.out(), out.inlet());
      }
    }).run(materializer);

    final Integer result = Await.result(future, Duration.create(300, TimeUnit.MILLISECONDS));
    assertEquals(1111, (int) result);
  }

  @Test
  public void mustBeAbleToUseMatValue() throws Exception {
    final Source<Integer, BoxedUnit> in1 = Source.single(1);
    final TestProbe probe = TestProbe.apply(system);

    final Future<Integer> future = FlowGraph.factory().closed(Sink.<Integer> head(), new Procedure2<Builder<Future<Integer>>, SinkShape<Integer>>() {
      @Override
      public void apply(Builder<Future<Integer>> b, SinkShape<Integer> out) throws Exception {
        b.from(Source.single(1)).to(out);
        b.from(b.materializedValue()).to(Sink.foreach(new Procedure<Future<Integer>>(){
          public void apply(Future<Integer> mat) throws Exception {
            Patterns.pipe(mat, system.dispatcher()).to(probe.ref());
          }
        }));
      }
    }).run(materializer);

    final Integer result = Await.result(future, Duration.create(300, TimeUnit.MILLISECONDS));
    assertEquals(1, (int) result);

    probe.expectMsg(1);
  }

}
