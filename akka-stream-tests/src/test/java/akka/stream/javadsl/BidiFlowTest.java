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
import akka.stream.javadsl.FlowGraph.Builder;
import akka.stream.javadsl.japi.*;
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
      .factory()
      .create(
          new Function<FlowGraph.Builder, BidiShape<Integer, Long, ByteString, String>>() {
            @Override
            public BidiShape<Integer, Long, ByteString, String> apply(Builder b)
                throws Exception {
              final FlowShape<Integer, Long> top = b.graph(Flow
                  .<Integer> empty().map(new Function<Integer, Long>() {
                    @Override
                    public Long apply(Integer arg) {
                      return (long) ((int) arg) + 2;
                    }
                  }));
              final FlowShape<ByteString, String> bottom = b.graph(Flow
                  .<ByteString> empty().map(new Function<ByteString, String>() {
                    @Override
                    public String apply(ByteString arg) {
                      return arg.decodeString("UTF-8");
                    }
                  }));
              return new BidiShape<Integer, Long, ByteString, String>(top
                  .inlet(), top.outlet(), bottom.inlet(), bottom.outlet());
            }
          });

  private final BidiFlow<Long, Integer, String, ByteString, BoxedUnit> inverse = BidiFlow
      .factory()
      .create(
          new Function<FlowGraph.Builder, BidiShape<Long, Integer, String, ByteString>>() {
            @Override
            public BidiShape<Long, Integer, String, ByteString> apply(Builder b)
                throws Exception {
              final FlowShape<Long, Integer> top = b.graph(Flow.<Long> empty()
                  .map(new Function<Long, Integer>() {
                    @Override
                    public Integer apply(Long arg) {
                      return (int) ((long) arg) + 2;
                    }
                  }));
              final FlowShape<String, ByteString> bottom = b.graph(Flow
                  .<String> empty().map(new Function<String, ByteString>() {
                    @Override
                    public ByteString apply(String arg) {
                      return ByteString.fromString(arg);
                    }
                  }));
              return new BidiShape<Long, Integer, String, ByteString>(top
                  .inlet(), top.outlet(), bottom.inlet(), bottom.outlet());
            }
          });

  private final BidiFlow<Integer, Long, ByteString, String, Future<Integer>> bidiMat = BidiFlow
      .factory()
      .create(
          Sink.<Integer> head(),
          new Function2<FlowGraph.Builder, SinkShape<Integer>, BidiShape<Integer, Long, ByteString, String>>() {
            @Override
            public BidiShape<Integer, Long, ByteString, String> apply(Builder b, SinkShape<Integer> sink)
                throws Exception {
              b.from(Source.single(42)).to(sink);
              final FlowShape<Integer, Long> top = b.graph(Flow
                  .<Integer> empty().map(new Function<Integer, Long>() {
                    @Override
                    public Long apply(Integer arg) {
                      return (long) ((int) arg) + 2;
                    }
                  }));
              final FlowShape<ByteString, String> bottom = b.graph(Flow
                  .<ByteString> empty().map(new Function<ByteString, String>() {
                    @Override
                    public String apply(ByteString arg) {
                      return arg.decodeString("UTF-8");
                    }
                  }));
              return new BidiShape<Integer, Long, ByteString, String>(top
                  .inlet(), top.outlet(), bottom.inlet(), bottom.outlet());
            }
          });

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
    final Pair<Future<Long>, Future<String>> p = FlowGraph
        .factory()
        .closed(Sink.<Long> head(), Sink.<String> head(),
            Keep.<Future<Long>, Future<String>> both(),
            new Procedure3<Builder, SinkShape<Long>, SinkShape<String>>() {
              @Override
              public void apply(Builder b, SinkShape<Long> st,
                  SinkShape<String> sb) throws Exception {
                final BidiShape<Integer, Long, ByteString, String> s = b
                    .graph(bidi);
                b.from(Source.single(1)).to(s.in1());
                b.from(s.out1()).to(st);
                b.from(Source.single(bytes)).to(s.in2());
                b.from(s.out2()).to(sb);
              }
            }).run(materializer);

    final Long rt = Await.result(p.first(), oneSec);
    final String rb = Await.result(p.second(), oneSec);

    assertEquals((Long) 3L, rt);
    assertEquals(str, rb);
  }

  @Test
  public void mustWorkAsAFlowThatIsOpenOnTheLeft() throws Exception {
    final Flow<Integer, String, BoxedUnit> f = bidi.join(Flow.<Long> empty().map(
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
    final Flow<ByteString, Long, BoxedUnit> f = Flow.<String> empty().map(
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
    final Flow<Integer,String,BoxedUnit> f = bidi.atop(inverse).join(Flow.<Integer> empty().map(
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
    final Flow<Integer,String,BoxedUnit> f = Flow.<Integer> empty().map(
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
    final Future<Integer> f = FlowGraph.factory().closed(bidiMat, new Procedure2<Builder, BidiShape<Integer, Long, ByteString, String>>() {
      @Override
      public void apply(Builder b,
          BidiShape<Integer, Long, ByteString, String> shape) throws Exception {
        final FlowShape<String, Integer> left = b.graph(Flow.<String> empty().map(
            new Function<String, Integer>() {
              @Override public Integer apply(String arg) {
                return Integer.valueOf(arg);
              }
            }));
        final FlowShape<Long, ByteString> right = b.graph(Flow.<Long> empty().map(
            new Function<Long, ByteString>() {
              @Override public ByteString apply(Long arg) {
                return ByteString.fromString("Hello " + arg);
              }
            }));
        b.from(shape.out2()).via(left).to(shape.in1())
         .from(shape.out1()).via(right).to(shape.in2());
      }
    }).run(materializer);
    assertEquals((Integer) 42, Await.result(f, oneSec));
  }
  
  @Test
  public void mustCombineMaterializationValues() throws Exception {
    final Flow<String, Integer, Future<Integer>> left = Flow.factory().create(
        Sink.<Integer> head(), new Function2<Builder, SinkShape<Integer>, Pair<Inlet<String>, Outlet<Integer>>>() {
          @Override
          public Pair<Inlet<String>, Outlet<Integer>> apply(Builder b,
              SinkShape<Integer> sink) throws Exception {
            final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.<Integer> create(2));
            final UniformFanInShape<Integer, Integer> merge = b.graph(Merge.<Integer> create(2));
            final FlowShape<String, Integer> flow = b.graph(Flow.<String> empty().map(
                new Function<String, Integer>() {
                      @Override
                      public Integer apply(String arg) {
                        return Integer.valueOf(arg);
                      }
                    }));
            b.from(bcast).to(sink)
             .from(Source.single(1)).via(bcast).to(merge)
             .from(flow).to(merge);
            return new Pair<Inlet<String>, Outlet<Integer>>(flow.inlet(), merge.out());
          }
        });
    final Flow<Long, ByteString, Future<List<Long>>> right = Flow.factory().create(
        Sink.<List<Long>> head(), new Function2<Builder, SinkShape<List<Long>>, Pair<Inlet<Long>, Outlet<ByteString>>>() {
          @Override
          public Pair<Inlet<Long>, Outlet<ByteString>> apply(Builder b,
              SinkShape<List<Long>> sink) throws Exception {
            final FlowShape<Long, List<Long>> flow = b.graph(Flow.<Long> empty().grouped(10));
            b.from(flow).to(sink);
            return new Pair<Inlet<Long>, Outlet<ByteString>>(flow.inlet(), b.source(Source.single(ByteString.fromString("10"))));
          }
        });
    final Pair<Pair<Future<Integer>, Future<Integer>>, Future<List<Long>>> result =
        left.join(bidiMat, Keep.<Future<Integer>, Future<Integer>> both()).join(right, Keep.<Pair<Future<Integer>, Future<Integer>>, Future<List<Long>>> both()).run(materializer);
    final Future<Integer> l = result.first().first();
    final Future<Integer> m = result.first().second();
    final Future<List<Long>> r = result.second();
    assertEquals((Integer) 1, Await.result(l, oneSec));
    assertEquals((Integer) 42, Await.result(m, oneSec));
    final Long[] rr = Await.result(r, oneSec).toArray(new Long[0]);
    Arrays.sort(rr);
    assertArrayEquals(new Long[] { 3L, 12L }, rr);
  }
}
