/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.testkit.AkkaSpec;
import akka.stream.javadsl.GraphDSL.Builder;
import akka.japi.function.*;
import akka.util.ByteString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

public class BidiFlowTest extends StreamTest {
  public BidiFlowTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource(
      "FlowTest", AkkaSpec.testConf());

  private final BidiFlow<Integer, Long, ByteString, String, BoxedUnit> bidi = BidiFlow
      .fromGraph(GraphDSL.create(
              new Function<GraphDSL.Builder<BoxedUnit>, BidiShape<Integer, Long, ByteString, String>>() {
                  @Override
                  public BidiShape<Integer, Long, ByteString, String> apply(Builder<BoxedUnit> b)
                          throws Exception {
                      final FlowShape<Integer, Long> top = b.add(Flow
                        .of(Integer.class).map(new Function<Integer, Long>() {
                          @Override
                          public Long apply(Integer arg) {
                            return (long) ((int) arg) + 2;
                          }
                        }));
                      final FlowShape<ByteString, String> bottom = b.add(Flow
                        .of(ByteString.class).map(new Function<ByteString, String>() {
                          @Override
                          public String apply(ByteString arg) {
                            return arg.decodeString("UTF-8");
                          }
                        }));
                      return new BidiShape<Integer, Long, ByteString, String>(top
                              .inlet(), top.outlet(), bottom.inlet(), bottom.outlet());
                  }
              }));

  private final BidiFlow<Long, Integer, String, ByteString, BoxedUnit> inverse = BidiFlow
      .fromGraph(
              GraphDSL.create(
                      new Function<GraphDSL.Builder<BoxedUnit>, BidiShape<Long, Integer, String, ByteString>>() {
                          @Override
                          public BidiShape<Long, Integer, String, ByteString> apply(Builder<BoxedUnit> b)
                                  throws Exception {
                              final FlowShape<Long, Integer> top = b.add(Flow.of(Long.class)
                                .map(new Function<Long, Integer>() {
                                  @Override
                                  public Integer apply(Long arg) {
                                    return (int) ((long) arg) + 2;
                                  }
                                }));
                              final FlowShape<String, ByteString> bottom = b.add(Flow
                                .of(String.class).map(new Function<String, ByteString>() {
                                  @Override
                                  public ByteString apply(String arg) {
                                    return ByteString.fromString(arg);
                                  }
                                }));
                              return new BidiShape<Long, Integer, String, ByteString>(top
                                      .inlet(), top.outlet(), bottom.inlet(), bottom.outlet());
                          }
                      }));

  private final BidiFlow<Integer, Long, ByteString, String, Future<Integer>> bidiMat =
    BidiFlow.fromGraph(
      GraphDSL.create(
        Sink.<Integer>head(),
        new Function2<GraphDSL.Builder<Future<Integer>>, SinkShape<Integer>, BidiShape<Integer, Long, ByteString, String>>() {
          @Override
          public BidiShape<Integer, Long, ByteString, String> apply(Builder<Future<Integer>> b, SinkShape<Integer> sink)
            throws Exception {
            b.from(b.add(Source.single(42))).to(sink);
            final FlowShape<Integer, Long> top = b.add(Flow
              .of(Integer.class).map(new Function<Integer, Long>() {
                @Override
                public Long apply(Integer arg) {
                  return (long) ((int) arg) + 2;
                }
              }));
            final FlowShape<ByteString, String> bottom = b.add(Flow
              .of(ByteString.class).map(new Function<ByteString, String>() {
                @Override
                public String apply(ByteString arg) {
                  return arg.decodeString("UTF-8");
                }
              }));
            return new BidiShape<Integer, Long, ByteString, String>(top
              .inlet(), top.outlet(), bottom.inlet(), bottom.outlet());
          }
        }));

  private final String str = "Hello World";
  private final ByteString bytes = ByteString.fromString(str);
  private final List<Integer> list = new ArrayList<Integer>();
  {
    list.add(1);
    list.add(2);
    list.add(3);
  }
  private final FiniteDuration oneSec = Duration.create(1, TimeUnit.SECONDS);

  @Test
  public void mustWorkInIsolation() throws Exception {
    final Pair<Future<Long>, Future<String>> p =
      RunnableGraph.fromGraph(GraphDSL
        .create(Sink.<Long> head(), Sink.<String> head(),
          Keep.<Future<Long>, Future<String>> both(),
          new Function3<Builder<Pair<Future<Long>, Future<String>>>, SinkShape<Long>, SinkShape<String>, ClosedShape>() {
            @Override
            public ClosedShape apply(Builder<Pair<Future<Long>, Future<String>>> b, SinkShape<Long> st,
                SinkShape<String> sb) throws Exception {
            final BidiShape<Integer, Long, ByteString, String> s =
              b.add(bidi);
              b.from(b.add(Source.single(1))).toInlet(s.in1());
              b.from(s.out1()).to(st);
              b.from(b.add(Source.single(bytes))).toInlet(s.in2());
              b.from(s.out2()).to(sb);
              return ClosedShape.getInstance();
            }
          })).run(materializer);

    final Long rt = Await.result(p.first(), oneSec);
    final String rb = Await.result(p.second(), oneSec);

    assertEquals((Long) 3L, rt);
    assertEquals(str, rb);
  }

  @Test
  public void mustWorkAsAFlowThatIsOpenOnTheLeft() throws Exception {
    final Flow<Integer, String, BoxedUnit> f = bidi.join(Flow.of(Long.class).map(
        new Function<Long, ByteString>() {
          @Override public ByteString apply(Long arg) {
            return ByteString.fromString("Hello " + arg);
          }
        }));
    final Future<List<String>> result = Source.from(list).via(f).grouped(10).runWith(Sink.<List<String>> head(), materializer);
    assertEquals(Arrays.asList("Hello 3", "Hello 4", "Hello 5"), Await.result(result, oneSec));
  }

  @Test
  public void mustWorkAsAFlowThatIsOpenOnTheRight() throws Exception {
    final Flow<ByteString, Long, BoxedUnit> f = Flow.of(String.class).map(
        new Function<String, Integer>() {
          @Override public Integer apply(String arg) {
            return Integer.valueOf(arg);
          }
        }).join(bidi);
    final List<ByteString> inputs = Arrays.asList(ByteString.fromString("1"), ByteString.fromString("2"));
    final Future<List<Long>> result = Source.from(inputs).via(f).grouped(10).runWith(Sink.<List<Long>> head(), materializer);
    assertEquals(Arrays.asList(3L, 4L), Await.result(result, oneSec));
  }

  @Test
  public void mustWorkWhenAtopItsInverse() throws Exception {
    final Flow<Integer,String,BoxedUnit> f = bidi.atop(inverse).join(Flow.of(Integer.class).map(
        new Function<Integer, String>() {
          @Override public String apply(Integer arg) {
            return arg.toString();
          }
        }));
    final Future<List<String>> result = Source.from(list).via(f).grouped(10).runWith(Sink.<List<String>> head(), materializer);
    assertEquals(Arrays.asList("5", "6", "7"), Await.result(result, oneSec));
  }

  @Test
  public void mustWorkWhenReversed() throws Exception {
    final Flow<Integer,String,BoxedUnit> f = Flow.of(Integer.class).map(
        new Function<Integer, String>() {
          @Override public String apply(Integer arg) {
            return arg.toString();
          }
        }).join(inverse.reversed()).join(bidi.reversed());
    final Future<List<String>> result = Source.from(list).via(f).grouped(10).runWith(Sink.<List<String>> head(), materializer);
    assertEquals(Arrays.asList("5", "6", "7"), Await.result(result, oneSec));
  }

  @Test
  public void mustMaterializeToItsValue() throws Exception {
    final Future<Integer> f = RunnableGraph.fromGraph(
      GraphDSL.create(bidiMat,
        new Function2<Builder<Future<Integer> >, BidiShape<Integer, Long, ByteString, String>, ClosedShape>() {
      @Override
      public ClosedShape apply(Builder<Future<Integer>> b,
          BidiShape<Integer, Long, ByteString, String> shape) throws Exception {
        final FlowShape<String, Integer> left = b.add(Flow.of(String.class).map(
          new Function<String, Integer>() {
            @Override
            public Integer apply(String arg) {
              return Integer.valueOf(arg);
            }
          }));
        final FlowShape<Long, ByteString> right = b.add(Flow.of(Long.class).map(
          new Function<Long, ByteString>() {
            @Override
            public ByteString apply(Long arg) {
              return ByteString.fromString("Hello " + arg);
            }
          }));
        b.from(shape.out2()).via(left).toInlet(shape.in1())
         .from(shape.out1()).via(right).toInlet(shape.in2());
        return ClosedShape.getInstance();
      }
    })).run(materializer);
    assertEquals((Integer) 42, Await.result(f, oneSec));
  }

  @Test
  public void mustCombineMaterializationValues() throws Exception {
    final Flow<String, Integer, Future<Integer>> left = Flow.fromGraph(GraphDSL.create(
            Sink.<Integer>head(), new Function2<Builder<Future<Integer>>, SinkShape<Integer>, FlowShape<String, Integer>>() {
                @Override
                public FlowShape<String, Integer> apply(Builder<Future<Integer>> b,
                                                        SinkShape<Integer> sink) throws Exception {
                    final UniformFanOutShape<Integer, Integer> bcast = b.add(Broadcast.<Integer>create(2));
                    final UniformFanInShape<Integer, Integer> merge = b.add(Merge.<Integer>create(2));
                    final FlowShape<String, Integer> flow = b.add(Flow.of(String.class).map(
                      new Function<String, Integer>() {
                        @Override
                        public Integer apply(String arg) {
                          return Integer.valueOf(arg);
                        }
                      }));
                    b.from(bcast).to(sink)
                            .from(b.add(Source.single(1))).viaFanOut(bcast).toFanIn(merge)
                            .from(flow).toFanIn(merge);
                    return new FlowShape<String, Integer>(flow.inlet(), merge.out());
                }
            }));
    final Flow<Long, ByteString, Future<List<Long>>> right = Flow.fromGraph(GraphDSL.create(
            Sink.<List<Long>>head(), new Function2<Builder<Future<List<Long>>>, SinkShape<List<Long>>, FlowShape<Long, ByteString>>() {
                @Override
                public FlowShape<Long, ByteString> apply(Builder<Future<List<Long>>> b,
                                                         SinkShape<List<Long>> sink) throws Exception {
                    final FlowShape<Long, List<Long>> flow = b.add(Flow.of(Long.class).grouped(10));
                    b.from(flow).to(sink);
                    return new FlowShape<Long, ByteString>(flow.inlet(), b.add(Source.single(ByteString.fromString("10"))).outlet());
                }
            }));
    final Pair<Pair<Future<Integer>, Future<Integer>>, Future<List<Long>>> result =
        left.joinMat(bidiMat, Keep.<Future<Integer>, Future<Integer>> both()).joinMat(right, Keep.<Pair<Future<Integer>, Future<Integer>>, Future<List<Long>>> both()).run(materializer);
    final Future<Integer> l = result.first().first();
    final Future<Integer> m = result.first().second();
    final Future<List<Long>> r = result.second();
    assertEquals((Integer) 1, Await.result(l, oneSec));
    assertEquals((Integer) 42, Await.result(m, oneSec));
    final Long[] rr = Await.result(r, oneSec).toArray(new Long[2]);
    Arrays.sort(rr);
    assertArrayEquals(new Long[] { 3L, 12L }, rr);
  }
}
