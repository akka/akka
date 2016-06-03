/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.NotUsed;
import org.junit.ClassRule;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import akka.japi.Pair;
import akka.stream.*;
import akka.testkit.AkkaSpec;
import akka.stream.javadsl.GraphDSL.Builder;
import akka.japi.function.*;
import akka.util.ByteString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import akka.testkit.AkkaJUnitActorSystemResource;

public class BidiFlowTest extends StreamTest {
  public BidiFlowTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource(
      "FlowTest", AkkaSpec.testConf());

  private final BidiFlow<Integer, Long, ByteString, String, NotUsed> bidi = BidiFlow
      .fromGraph(GraphDSL.create(
              new Function<GraphDSL.Builder<NotUsed>, BidiShape<Integer, Long, ByteString, String>>() {
                  @Override
                  public BidiShape<Integer, Long, ByteString, String> apply(Builder<NotUsed> b)
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
                              .in(), top.out(), bottom.in(), bottom.out());
                  }
              }));

  private final BidiFlow<Long, Integer, String, ByteString, NotUsed> inverse = BidiFlow
      .fromGraph(
              GraphDSL.create(
                      new Function<GraphDSL.Builder<NotUsed>, BidiShape<Long, Integer, String, ByteString>>() {
                          @Override
                          public BidiShape<Long, Integer, String, ByteString> apply(Builder<NotUsed> b)
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
                                      .in(), top.out(), bottom.in(), bottom.out());
                          }
                      }));

  private final BidiFlow<Integer, Long, ByteString, String, CompletionStage<Integer>> bidiMat =
    BidiFlow.fromGraph(
      GraphDSL.create(
        Sink.<Integer>head(),
        (b, sink) -> {
            b.from(b.add(Source.single(42))).to(sink);
            final FlowShape<Integer, Long> top = b.add(Flow
              .of(Integer.class).map(i -> (long)(i + 2)));
            final FlowShape<ByteString, String> bottom = b.add(Flow
              .of(ByteString.class).map(bytes -> bytes.decodeString("UTF-8")));
            return new BidiShape<Integer, Long, ByteString, String>(top
              .in(), top.out(), bottom.in(), bottom.out());
          }
        ));

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
    final Pair<CompletionStage<Long>, CompletionStage<String>> p =
      RunnableGraph.fromGraph(GraphDSL
        .create(Sink.<Long> head(), Sink.<String> head(),
          Keep.both(),
          (b, st, sb) -> {
            final BidiShape<Integer, Long, ByteString, String> s =
              b.add(bidi);
              b.from(b.add(Source.single(1))).toInlet(s.in1());
              b.from(s.out1()).to(st);
              b.from(b.add(Source.single(bytes))).toInlet(s.in2());
              b.from(s.out2()).to(sb);
              return ClosedShape.getInstance();
          })).run(materializer);

    final Long rt = p.first().toCompletableFuture().get(1, TimeUnit.SECONDS);
    final String rb = p.second().toCompletableFuture().get(1, TimeUnit.SECONDS);

    assertEquals((Long) 3L, rt);
    assertEquals(str, rb);
  }

  @Test
  public void mustWorkAsAFlowThatIsOpenOnTheLeft() throws Exception {
    final Flow<Integer, String, NotUsed> f = bidi.join(Flow.of(Long.class).map(
        new Function<Long, ByteString>() {
          @Override public ByteString apply(Long arg) {
            return ByteString.fromString("Hello " + arg);
          }
        }));

    final CompletionStage<List<String>> result = Source.from(list).via(f).limit(10).runWith(Sink.<String>seq(), materializer);
    assertEquals(Arrays.asList("Hello 3", "Hello 4", "Hello 5"), result.toCompletableFuture().get(1, TimeUnit.SECONDS));
  }

  @Test
  public void mustWorkAsAFlowThatIsOpenOnTheRight() throws Exception {
    final Flow<ByteString, Long, NotUsed> f = Flow.of(String.class).map(
        new Function<String, Integer>() {
          @Override public Integer apply(String arg) {
            return Integer.valueOf(arg);
          }
        }).join(bidi);
    final List<ByteString> inputs = Arrays.asList(ByteString.fromString("1"), ByteString.fromString("2"));
    final CompletionStage<List<Long>> result = Source.from(inputs).via(f).limit(10).runWith(Sink.<Long>seq(), materializer);
    assertEquals(Arrays.asList(3L, 4L), result.toCompletableFuture().get(1, TimeUnit.SECONDS));
  }

  @Test
  public void mustWorkWhenAtopItsInverse() throws Exception {
    final Flow<Integer,String,NotUsed> f = bidi.atop(inverse).join(Flow.of(Integer.class).map(
        new Function<Integer, String>() {
          @Override public String apply(Integer arg) {
            return arg.toString();
          }
        }));
    final CompletionStage<List<String>> result = Source.from(list).via(f).limit(10).runWith(Sink.<String>seq(), materializer);
    assertEquals(Arrays.asList("5", "6", "7"), result.toCompletableFuture().get(1, TimeUnit.SECONDS));
  }

  @Test
  public void mustWorkWhenReversed() throws Exception {
    final Flow<Integer,String,NotUsed> f = Flow.of(Integer.class).map(
        new Function<Integer, String>() {
          @Override public String apply(Integer arg) {
            return arg.toString();
          }
        }).join(inverse.reversed()).join(bidi.reversed());
    final CompletionStage<List<String>> result = Source.from(list).via(f).limit(10).runWith(Sink.<String>seq(), materializer);
    assertEquals(Arrays.asList("5", "6", "7"), result.toCompletableFuture().get(1, TimeUnit.SECONDS));
  }

  @Test
  public void mustMaterializeToItsValue() throws Exception {
    final CompletionStage<Integer> f = RunnableGraph.fromGraph(
      GraphDSL.create(bidiMat, (b, shape) -> {
        final FlowShape<String, Integer> left = b.add(Flow.of(String.class).map(Integer::valueOf));
        final FlowShape<Long, ByteString> right = b.add(Flow.of(Long.class).map(s -> ByteString.fromString("Hello " + s)));
        b.from(shape.out2()).via(left).toInlet(shape.in1())
         .from(shape.out1()).via(right).toInlet(shape.in2());
        return ClosedShape.getInstance();
    })).run(materializer);
    assertEquals((Integer) 42, f.toCompletableFuture().get(1, TimeUnit.SECONDS));
  }

  @Test
  public void mustCombineMaterializationValues() throws Exception {
    final Flow<String, Integer, CompletionStage<Integer>> left = Flow.fromGraph(GraphDSL.create(
            Sink.<Integer>head(), (b, sink) -> {
                    final UniformFanOutShape<Integer, Integer> bcast = b.add(Broadcast.<Integer>create(2));
                    final UniformFanInShape<Integer, Integer> merge = b.add(Merge.<Integer>create(2));
                    final FlowShape<String, Integer> flow = b.add(Flow.of(String.class).map(Integer::valueOf));
                    b.from(bcast).to(sink)
                            .from(b.add(Source.single(1))).viaFanOut(bcast).toFanIn(merge)
                            .from(flow).toFanIn(merge);
                    return new FlowShape<String, Integer>(flow.in(), merge.out());
            }));
    final Flow<Long, ByteString, CompletionStage<List<Long>>> right = Flow.fromGraph(GraphDSL.create(
            Sink.<List<Long>>head(), (b, sink) -> {
                    final FlowShape<Long, List<Long>> flow = b.add(Flow.of(Long.class).grouped(10));
                    b.from(flow).to(sink);
                    return new FlowShape<Long, ByteString>(flow.in(), b.add(Source.single(ByteString.fromString("10"))).out());
            }));
    final Pair<Pair<CompletionStage<Integer>, CompletionStage<Integer>>, CompletionStage<List<Long>>> result =
        left.joinMat(bidiMat, Keep.both()).joinMat(right, Keep.both()).run(materializer);
    final CompletionStage<Integer> l = result.first().first();
    final CompletionStage<Integer> m = result.first().second();
    final CompletionStage<List<Long>> r = result.second();
    assertEquals((Integer) 1, l.toCompletableFuture().get(1, TimeUnit.SECONDS));
    assertEquals((Integer) 42, m.toCompletableFuture().get(1, TimeUnit.SECONDS));
    final Long[] rr = r.toCompletableFuture().get(1, TimeUnit.SECONDS).toArray(new Long[2]);
    Arrays.sort(rr);
    assertArrayEquals(new Long[] { 3L, 12L }, rr);
  }
  
  public void mustSuitablyOverrideAttributeHandlingMethods() {
    @SuppressWarnings("unused")
    final BidiFlow<Integer, Long, ByteString, String, NotUsed> b =
        bidi.withAttributes(Attributes.name("")).addAttributes(Attributes.asyncBoundary()).named("");
  }
}
