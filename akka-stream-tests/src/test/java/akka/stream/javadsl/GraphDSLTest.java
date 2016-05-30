/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl;

import akka.NotUsed;
import akka.japi.Pair;
import akka.pattern.PatternsCS;
import akka.japi.tuple.Tuple4;
import akka.stream.*;
import akka.stream.javadsl.GraphDSL.Builder;
import akka.stream.stage.*;
import akka.japi.function.*;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.testkit.AkkaJUnitActorSystemResource;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;

public class GraphDSLTest extends StreamTest {
  public GraphDSLTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("GraphDSLTest",
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
    final Flow<String, String, NotUsed> f1 =
        Flow.of(String.class).transform(GraphDSLTest.this.<String> op()).named("f1");
    final Flow<String, String, NotUsed> f2 =
        Flow.of(String.class).transform(GraphDSLTest.this.<String> op()).named("f2");
    @SuppressWarnings("unused")
    final Flow<String, String, NotUsed> f3 =
        Flow.of(String.class).transform(GraphDSLTest.this.<String> op()).named("f3");

    final Source<String, NotUsed> in1 = Source.from(Arrays.asList("a", "b", "c"));
    final Source<String, NotUsed> in2 = Source.from(Arrays.asList("d", "e", "f"));

    final Sink<String, Publisher<String>> publisher = Sink.asPublisher(AsPublisher.WITHOUT_FANOUT);

    final Source<String, NotUsed> source = Source.fromGraph(
            GraphDSL.create(new Function<GraphDSL.Builder<NotUsed>, SourceShape<String>>() {
              @Override
              public SourceShape<String> apply(Builder<NotUsed> b) throws Exception {
                final UniformFanInShape<String, String> merge = b.add(Merge.<String>create(2));
                b.from(b.add(in1)).via(b.add(f1)).toInlet(merge.in(0));
                b.from(b.add(in2)).via(b.add(f2)).toInlet(merge.in(1));
                return new SourceShape<String>(merge.out());
              }
            }));

    // collecting
    final Publisher<String> pub = source.runWith(publisher, materializer);
    final CompletionStage<List<String>> all = Source.fromPublisher(pub).limit(100).runWith(Sink.<String>seq(), materializer);

    final List<String> result = all.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(new HashSet<Object>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<String>(result));
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> input2 = Arrays.asList(1, 2, 3);

    RunnableGraph.fromGraph( GraphDSL.create(
      new Function<Builder<NotUsed>,ClosedShape>() {
        @Override
        public ClosedShape apply(final Builder<NotUsed> b) throws Exception {
          final Source<String, NotUsed> in1 = Source.from(input1);
          final Source<Integer, NotUsed> in2 = Source.from(input2);
          final FanInShape2<String, Integer, Pair<String,Integer>> zip = b.add(Zip.<String, Integer>create());
          final Sink<Pair<String, Integer>, NotUsed> out = createSink(probe);

          b.from(b.add(in1)).toInlet(zip.in0());
          b.from(b.add(in2)).toInlet(zip.in1());
          b.from(zip.out()).to(b.add(out));
          return ClosedShape.getInstance();
        }
      })).run(materializer);

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

    RunnableGraph.fromGraph(GraphDSL.create(
        new Function<Builder<NotUsed>, ClosedShape>() {
          @Override
          public ClosedShape apply(final Builder<NotUsed> b) throws Exception {
            final SourceShape<Pair<String, Integer>> in = b.add(Source.from(input));
            final FanOutShape2<Pair<String, Integer>, String, Integer> unzip = b.add(Unzip.<String, Integer>create());

            final SinkShape<String> out1 = b.add(GraphDSLTest.<String>createSink(probe1));
            final SinkShape<Integer> out2 = b.add(GraphDSLTest.<Integer>createSink(probe2));

            b.from(in).toInlet(unzip.in());
            b.from(unzip.out0()).to(out1);
            b.from(unzip.out1()).to(out2);
            return ClosedShape.getInstance();
          }
        })).run(materializer);

    List<Object> output1 = Arrays.asList(probe1.receiveN(3));
    List<Object> output2 = Arrays.asList(probe2.receiveN(3));
    assertEquals(expected1, output1);
    assertEquals(expected2, output2);
  }

  private static <T> Sink<T, NotUsed> createSink(final JavaTestKit probe){
    return Sink.actorRef(probe.getRef(), "onComplete");
  }

  @Test
  public void mustBeAbleToUseUnzipWith() throws Exception {
    final JavaTestKit probe1 = new JavaTestKit(system);
    final JavaTestKit probe2 = new JavaTestKit(system);

    RunnableGraph.fromGraph(GraphDSL.create(
      new Function<Builder<NotUsed>, ClosedShape>() {
        @Override
        public ClosedShape apply(final Builder<NotUsed> b) throws Exception {
          final Source<Integer, NotUsed> in = Source.single(1);

          final FanOutShape2<Integer, String, Integer> unzip = b.add(UnzipWith.create(
              new Function<Integer, Pair<String, Integer>>() {
                @Override
                public Pair<String, Integer> apply(Integer l) throws Exception {
                  return new Pair<String, Integer>(l + "!", l);
                }
              })
          );

          final SinkShape<String> out1 = b.add(GraphDSLTest.<String>createSink(probe1));
          final SinkShape<Integer> out2 = b.add(GraphDSLTest.<Integer>createSink(probe2));

          b.from(b.add(in)).toInlet(unzip.in());
          b.from(unzip.out0()).to(out1);
          b.from(unzip.out1()).to(out2);
          return ClosedShape.getInstance();
        }
      }
    )).run(materializer);

    Duration d = Duration.create(3, TimeUnit.SECONDS);

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

    RunnableGraph.fromGraph(GraphDSL.create(
      new Function<Builder<NotUsed>, ClosedShape>() {
        @Override
        public ClosedShape apply(final Builder<NotUsed> b) throws Exception {
          final Source<Integer, NotUsed> in = Source.single(1);

          final FanOutShape4<Integer, String, Integer, String, Integer> unzip = b.add(UnzipWith.create4(
              new Function<Integer, Tuple4<String, Integer, String, Integer>>() {
                @Override
                public Tuple4<String, Integer, String, Integer> apply(Integer l) throws Exception {
                  return new Tuple4<String, Integer, String, Integer>(l.toString(), l, l + "+" + l, l + l);
                }
              })
          );

          final SinkShape<String> out1 = b.add(GraphDSLTest.<String>createSink(probe1));
          final SinkShape<Integer> out2 = b.add(GraphDSLTest.<Integer>createSink(probe2));
          final SinkShape<String> out3 = b.add(GraphDSLTest.<String>createSink(probe3));
          final SinkShape<Integer> out4 = b.add(GraphDSLTest.<Integer>createSink(probe4));

          b.from(b.add(in)).toInlet(unzip.in());
          b.from(unzip.out0()).to(out1);
          b.from(unzip.out1()).to(out2);
          b.from(unzip.out2()).to(out3);
          b.from(unzip.out3()).to(out4);
          return ClosedShape.getInstance();
        }
      })).run(materializer);

    Duration d = Duration.create(3, TimeUnit.SECONDS);

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
    final Source<Integer, NotUsed> in1 = Source.single(1);
    final Source<Integer, NotUsed> in2 = Source.single(10);

    final Graph<FanInShape2<Integer, Integer, Integer>, NotUsed> sumZip = ZipWith.create(
      new Function2<Integer, Integer, Integer>() {
        @Override public Integer apply(Integer l, Integer r) throws Exception {
          return l + r;
      }
    });

    final CompletionStage<Integer> future = RunnableGraph.fromGraph(GraphDSL.create(Sink.<Integer>head(),
      (b, out) -> {
        final FanInShape2<Integer, Integer, Integer> zip = b.add(sumZip);
        b.from(b.add(in1)).toInlet(zip.in0());
        b.from(b.add(in2)).toInlet(zip.in1());
        b.from(zip.out()).to(out);
        return ClosedShape.getInstance();
    })).run(materializer);

    final Integer result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(11, (int) result);
  }

  @Test
  public void mustBeAbleToUseZipN() throws Exception {
    final Source<Integer, NotUsed> in1 = Source.single(1);
    final Source<Integer, NotUsed> in2 = Source.single(10);

    final Graph<UniformFanInShape<Integer, List<Integer>>, NotUsed> sumZip = ZipN.create(2);

    final CompletionStage<List<Integer>> future = RunnableGraph.fromGraph(GraphDSL.create(Sink.<List<Integer>>head(),
      (b, out) -> {
        final UniformFanInShape<Integer, List<Integer>> zip = b.add(sumZip);
        b.from(b.add(in1)).toInlet(zip.in(0));
        b.from(b.add(in2)).toInlet(zip.in(1));
        b.from(zip.out()).to(out);
        return ClosedShape.getInstance();
    })).run(materializer);

    final List<Integer> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(1, 10), result);
  }

  @Test
  public void mustBeAbleToUseZipWithN() throws Exception {
    final Source<Integer, NotUsed> in1 = Source.single(1);
    final Source<Integer, NotUsed> in2 = Source.single(10);

    final Graph<UniformFanInShape<Integer, Integer>, NotUsed> sumZip = ZipWithN.create(
      new Function<List<Integer>, Integer>() {
        @Override public Integer apply(List<Integer> list) throws Exception {
          Integer sum = 0;

          for(Integer i : list) {
            sum += i;
          }

          return sum;
        }
    }, 2);

    final CompletionStage<Integer> future = RunnableGraph.fromGraph(GraphDSL.create(Sink.<Integer>head(),
      (b, out) -> {
        final UniformFanInShape<Integer, Integer> zip = b.add(sumZip);
        b.from(b.add(in1)).toInlet(zip.in(0));
        b.from(b.add(in2)).toInlet(zip.in(1));
        b.from(zip.out()).to(out);
        return ClosedShape.getInstance();
    })).run(materializer);

    final Integer result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(11, (int) result);
  }

  @Test
     public void mustBeAbleToUseZip4With() throws Exception {
    final Source<Integer, NotUsed> in1 = Source.single(1);
    final Source<Integer, NotUsed> in2 = Source.single(10);
    final Source<Integer, NotUsed> in3 = Source.single(100);
    final Source<Integer, NotUsed> in4 = Source.single(1000);

    final Graph<FanInShape4<Integer, Integer, Integer, Integer, Integer>, NotUsed> sumZip = ZipWith.create4(
            new Function4<Integer, Integer, Integer, Integer, Integer>() {
              @Override public Integer apply(Integer i1, Integer i2, Integer i3, Integer i4) throws Exception {
                return i1 + i2 + i3 + i4;
              }
            });

    final CompletionStage<Integer> future = RunnableGraph.fromGraph(
      GraphDSL.create(Sink.<Integer>head(), (b, out) -> {
        final FanInShape4<Integer, Integer, Integer, Integer, Integer> zip = b.add(sumZip);
        b.from(b.add(in1)).toInlet(zip.in0());
        b.from(b.add(in2)).toInlet(zip.in1());
        b.from(b.add(in3)).toInlet(zip.in2());
        b.from(b.add(in4)).toInlet(zip.in3());
        b.from(zip.out()).to(out);
        return ClosedShape.getInstance();
    })).run(materializer);

    final Integer result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(1111, (int) result);
  }

  @Test
  public void mustBeAbleToUseMatValue() throws Exception {
    @SuppressWarnings("unused")
    final Source<Integer, NotUsed> in1 = Source.single(1);
    final TestProbe probe = TestProbe.apply(system);

    final CompletionStage<Integer> future = RunnableGraph.fromGraph(
      GraphDSL.create(Sink.<Integer> head(), (b, out) -> {
        b.from(b.add(Source.single(1))).to(out);
        b.from(b.materializedValue()).to(b.add(Sink.foreach(mat -> PatternsCS.pipe(mat, system.dispatcher()).to(probe.ref()))));
        return ClosedShape.getInstance();
    })).run(materializer);

    final Integer result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(1, (int) result);

    probe.expectMsg(1);
  }

}
