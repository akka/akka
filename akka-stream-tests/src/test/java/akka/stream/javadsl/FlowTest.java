/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.dispatch.Foreach;
import akka.dispatch.Futures;
import akka.japi.JavaPartialFunction;
import akka.japi.Pair;
import akka.japi.function.*;
import akka.stream.*;
import akka.stream.impl.ConstantFun;
import akka.stream.javadsl.GraphDSL.Builder;
import akka.stream.stage.*;
import akka.testkit.AkkaSpec;
import akka.stream.testkit.TestPublisher;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static akka.stream.testkit.StreamTestKit.PublisherProbeSubscription;
import static org.junit.Assert.*;

@SuppressWarnings("serial")
public class FlowTest extends StreamTest {
  public FlowTest() {
    super(actorSystemResource);
  }

    @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  @Test
  public void mustBeAbleToUseSimpleOperators() {
    final JavaTestKit probe = new JavaTestKit(system);
    final String[] lookup = { "a", "b", "c", "d", "e", "f" };
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
    final Source<Integer, NotUsed> ints = Source.from(input);
    final Flow<Integer, String, NotUsed> flow1 = Flow.of(Integer.class).drop(2).take(3
    ).takeWithin(FiniteDuration.create(10, TimeUnit.SECONDS
    )).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return lookup[elem];
      }
    }).filter(new Predicate<String>() {
      public boolean test(String elem) {
        return !elem.equals("c");
      }
    });
    final Flow<String, String, NotUsed> flow2 = Flow.of(String.class).grouped(2
    ).mapConcat(new Function<java.util.List<String>, java.lang.Iterable<String>>() {
      public java.util.List<String> apply(java.util.List<String> elem) {
        return elem;
      }
    }).groupedWithin(100, FiniteDuration.create(50, TimeUnit.MILLISECONDS)
    ).mapConcat(new Function<java.util.List<String>, java.lang.Iterable<String>>() {
          public java.util.List<String> apply(java.util.List<String> elem) {
            return elem;
          }
        });

    ints.via(flow1.via(flow2))
      .runFold("", (acc, elem) -> acc + elem, materializer)
      .thenAccept(elem -> probe.getRef().tell(elem, ActorRef.noSender()));

    probe.expectMsgEquals("de");
  }

  @Test
  public void mustBeAbleToUseDropWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3));
    final Flow<Integer, Integer, NotUsed> flow =
        Flow.of(Integer.class).dropWhile(elem -> elem < 2);

    final CompletionStage<Done> future =
        source.via(flow).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToUseStatefulMaponcat() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
    final Source<Integer, NotUsed> ints = Source.from(input);
    final Flow<Integer, Integer, NotUsed> flow = Flow.of(Integer.class).statefulMapConcat(
            () -> {
              int[] state = new int[] {0};
              return (elem) -> {
                List<Integer> list = new ArrayList<>(Collections.nCopies(state[0], elem));
                state[0] = elem;
                return list;
              };
            });

    ints.via(flow)
            .runFold("", (acc, elem) -> acc + elem, materializer)
            .thenAccept(elem -> probe.getRef().tell(elem, ActorRef.noSender()));

    probe.expectMsgEquals("2334445555");
  }

  @Test
  public void mustBeAbleToUseIntersperse() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<String, NotUsed> source = Source.from(Arrays.asList("0", "1", "2", "3"));
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).intersperse("[", ",", "]");

    final CompletionStage<Done> future =
        source.via(flow).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgEquals("[");
    probe.expectMsgEquals("0");
    probe.expectMsgEquals(",");
    probe.expectMsgEquals("1");
    probe.expectMsgEquals(",");
    probe.expectMsgEquals("2");
    probe.expectMsgEquals(",");
    probe.expectMsgEquals("3");
    probe.expectMsgEquals("]");
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToUseIntersperseAndConcat() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<String, NotUsed> source = Source.from(Arrays.asList("0", "1", "2", "3"));
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).intersperse(",");

    final CompletionStage<Done> future =
        Source.single(">> ").concat(source.via(flow)).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgEquals(">> ");
    probe.expectMsgEquals("0");
    probe.expectMsgEquals(",");
    probe.expectMsgEquals("1");
    probe.expectMsgEquals(",");
    probe.expectMsgEquals("2");
    probe.expectMsgEquals(",");
    probe.expectMsgEquals("3");
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToUseTakeWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3));
    final Flow<Integer, Integer, NotUsed> flow = Flow.of(Integer.class).takeWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final CompletionStage<Done> future =
        source.via(flow).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);

    FiniteDuration duration = Duration.apply(200, TimeUnit.MILLISECONDS);

    probe.expectNoMsg(duration);
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }


  @Ignore("StatefulStage to be converted to GraphStage when Java Api is available (#18817)") @Test
  public void mustBeAbleToUseTransform() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
    // duplicate each element, stop after 4 elements, and emit sum to the end
    final Flow<Integer, Integer, NotUsed> flow = Flow.of(Integer.class).transform(new Creator<Stage<Integer, Integer>>() {
      @Override
      public PushPullStage<Integer, Integer> create() throws Exception {
        return new StatefulStage<Integer, Integer>() {
          int sum = 0;
          int count = 0;

          @Override
          public StageState<Integer, Integer> initial() {
            return new StageState<Integer, Integer>() {
              @Override
              public SyncDirective onPush(Integer element, Context<Integer> ctx) {
                sum += element;
                count += 1;
                if (count == 4) {
                  return emitAndFinish(Arrays.asList(element, element, sum).iterator(), ctx);
                } else {
                  return emit(Arrays.asList(element, element).iterator(), ctx);
                }
              }

            };
          }

          @Override
          public TerminationDirective onUpstreamFinish(Context<Integer> ctx) {
            return terminationEmit(Collections.singletonList(sum).iterator(), ctx);
          }

        };
      }
    });
    Source.from(input).via(flow).runForeach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals(6);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void mustBeAbleToUseGroupBy() throws Exception {
    final Iterable<String> input = Arrays.asList("Aaa", "Abb", "Bcc", "Cdd", "Cee");
    final Flow<String, List<String>, NotUsed> flow = Flow
        .of(String.class)
        .groupBy(3, new Function<String, String>() {
          public String apply(String elem) {
            return elem.substring(0, 1);
          }
        })
        .grouped(10)
        .mergeSubstreams();

    final CompletionStage<List<List<String>>> future =
        Source.from(input).via(flow).limit(10).runWith(Sink.<List<String>> seq(), materializer);
    final Object[] result = future.toCompletableFuture().get(1, TimeUnit.SECONDS).toArray();
    Arrays.sort(result, (Comparator<Object>)(Object) new Comparator<List<String>>() {
      @Override
      public int compare(List<String> o1, List<String> o2) {
        return o1.get(0).charAt(0) - o2.get(0).charAt(0);
      }
    });

    assertArrayEquals(new Object[] { Arrays.asList("Aaa", "Abb"), Arrays.asList("Bcc"), Arrays.asList("Cdd", "Cee") }, result);
  }

  @Test
  public void mustBeAbleToUseSplitWhen() throws Exception {
    final Iterable<String> input = Arrays.asList("A", "B", "C", ".", "D", ".", "E", "F");
    final Flow<String, List<String>, NotUsed> flow = Flow
    	.of(String.class)
    	.splitWhen(new Predicate<String>() {
    	  public boolean test(String elem) {
            return elem.equals(".");
          }
        })
    	.grouped(10)
    	.concatSubstreams();

    final CompletionStage<List<List<String>>> future =
        Source.from(input).via(flow).limit(10).runWith(Sink.<List<String>> seq(), materializer);
    final List<List<String>> result = future.toCompletableFuture().get(1, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(Arrays.asList("A", "B", "C"), Arrays.asList(".", "D"), Arrays.asList(".", "E", "F")), result);
  }

  @Test
  public void mustBeAbleToUseSplitAfter() throws Exception {
	final Iterable<String> input = Arrays.asList("A", "B", "C", ".", "D", ".", "E", "F");
	final Flow<String, List<String>, NotUsed> flow = Flow
	    .of(String.class)
	    .splitAfter(new Predicate<String>() {
	   	  public boolean test(String elem) {
	        return elem.equals(".");
	      }
	    })
	    .grouped(10)
	    .concatSubstreams();

    final CompletionStage<List<List<String>>> future =
        Source.from(input).via(flow).limit(10).runWith(Sink.<List<String>> seq(), materializer);
    final List<List<String>> result = future.toCompletableFuture().get(1, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(Arrays.asList("A", "B", "C", "."), Arrays.asList("D", "."), Arrays.asList("E", "F")), result);
  }

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
        Flow.of(String.class).transform(FlowTest.this.<String> op()).named("f1");
    final Flow<String, String, NotUsed> f2 =
        Flow.of(String.class).transform(FlowTest.this.<String> op()).named("f2");
    @SuppressWarnings("unused")
    final Flow<String, String, NotUsed> f3 =
        Flow.of(String.class).transform(FlowTest.this.<String> op()).named("f3");

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

    final List<String> result = all.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
    assertEquals(new HashSet<Object>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<String>(result));
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> input2 = Arrays.asList(1, 2, 3);

    RunnableGraph.fromGraph(GraphDSL.create(new Function<Builder<NotUsed>, ClosedShape>(){
      public ClosedShape apply(Builder<NotUsed> b) {
        final Outlet<String> in1 = b.add(Source.from(input1)).out();
        final Outlet<Integer> in2 = b.add(Source.from(input2)).out();
        final FanInShape2<String, Integer, Pair<String, Integer>> zip = b.add(Zip.<String, Integer>create());
        final SinkShape<Pair<String, Integer>> out =
          b.add(Sink.foreach(new Procedure<Pair<String, Integer>>() {
            @Override
            public void apply(Pair<String, Integer> param) throws Exception {
              probe.getRef().tell(param, ActorRef.noSender());
            }
          }));

        b.from(in1).toInlet(zip.in0());
        b.from(in2).toInlet(zip.in1());
        b.from(zip.out()).to(out);
        return ClosedShape.getInstance();
      }
    })).run(materializer);

    List<Object> output = Arrays.asList(probe.receiveN(3));
    List<Pair<String, Integer>> expected = Arrays.asList(new Pair<String, Integer>("A", 1), new Pair<String, Integer>(
        "B", 2), new Pair<String, Integer>("C", 3));
    assertEquals(expected, output);
  }

  @Test
  public void mustBeAbleToUseConcat() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    final Source<String, NotUsed> in1 = Source.from(input1);
    final Source<String, NotUsed> in2 = Source.from(input2);
    final Flow<String, String, NotUsed> flow = Flow.of(String.class);
    in1.via(flow.concat(in2)).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    List<Object> output = Arrays.asList(probe.receiveN(6));
    assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), output);
  }

  @Test
  public void mustBeAbleToUsePrepend() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    final Source<String, NotUsed> in1 = Source.from(input1);
    final Source<String, NotUsed> in2 = Source.from(input2);
    final Flow<String, String, NotUsed> flow = Flow.of(String.class);
    in2.via(flow.prepend(in1)).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    List<Object> output = Arrays.asList(probe.receiveN(6));
    assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), output);
  }

  @Test
  public void mustBeAbleToUsePrefixAndTail() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6);
    final Flow<Integer, Pair<List<Integer>, Source<Integer, NotUsed>>, NotUsed> flow = Flow.of(Integer.class).prefixAndTail(3);
    CompletionStage<Pair<List<Integer>, Source<Integer, NotUsed>>> future =
        Source.from(input).via(flow).runWith(Sink.<Pair<List<Integer>, Source<Integer, NotUsed>>>head(), materializer);
    Pair<List<Integer>, Source<Integer, NotUsed>> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(Arrays.asList(1, 2, 3), result.first());

    CompletionStage<List<Integer>> tailFuture = result.second().limit(4).runWith(Sink.<Integer>seq(), materializer);
    List<Integer> tailResult = tailFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(Arrays.asList(4, 5, 6), tailResult);
  }

  @Test
  public void mustBeAbleToUseConcatAllWithSources() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input1 = Arrays.asList(1, 2, 3);
    final Iterable<Integer> input2 = Arrays.asList(4, 5);

    final List<Source<Integer, NotUsed>> mainInputs = new ArrayList<Source<Integer,NotUsed>>();
    mainInputs.add(Source.from(input1));
    mainInputs.add(Source.from(input2));

    final Flow<Source<Integer, NotUsed>, List<Integer>, NotUsed> flow = Flow.<Source<Integer, NotUsed>>create().
      flatMapConcat(ConstantFun.<Source<Integer, NotUsed>>javaIdentityFunction()).grouped(6);
    CompletionStage<List<Integer>> future = Source.from(mainInputs).via(flow)
        .runWith(Sink.<List<Integer>>head(), materializer);

    List<Integer> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
  }

  @Test
  public void mustBeAbleToUseFlatMapMerge() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input1 = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    final Iterable<Integer> input2 = Arrays.asList(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
    final Iterable<Integer> input3 = Arrays.asList(20, 21, 22, 23, 24, 25, 26, 27, 28, 29);
    final Iterable<Integer> input4 = Arrays.asList(30, 31, 32, 33, 34, 35, 36, 37, 38, 39);

    final List<Source<Integer, NotUsed>> mainInputs = new ArrayList<Source<Integer,NotUsed>>();
    mainInputs.add(Source.from(input1));
    mainInputs.add(Source.from(input2));
    mainInputs.add(Source.from(input3));
    mainInputs.add(Source.from(input4));

    final Flow<Source<Integer, NotUsed>, List<Integer>, NotUsed> flow = Flow.<Source<Integer, NotUsed>>create().
      flatMapMerge(3, ConstantFun.<Source<Integer, NotUsed>>javaIdentityFunction()).grouped(60);
    CompletionStage<List<Integer>> future = Source.from(mainInputs).via(flow)
        .runWith(Sink.<List<Integer>>head(), materializer);

    List<Integer> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    final Set<Integer> set = new HashSet<Integer>();
    for (Integer i: result) {
      set.add(i);
    }
    final Set<Integer> expected = new HashSet<Integer>();
    for (int i = 0; i < 40; ++i) {
      expected.add(i);
    }

    assertEquals(expected, set);
  }

  @Test
  public void mustBeAbleToUseBuffer() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, List<String>, NotUsed> flow = Flow.of(String.class).buffer(2, OverflowStrategy.backpressure()).grouped(4);
    final CompletionStage<List<String>> future = Source.from(input).via(flow)
        .runWith(Sink.<List<String>>head(), materializer);

    List<String> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(input, result);
  }

  @Test
  public void mustBeAbleToUseWatchTermination() throws Exception {
    final List<String> input = Arrays.asList("A", "B", "C");
    CompletionStage<Done> future = Source.from(input)
            .watchTermination(Keep.right())
            .to(Sink.ignore()).run(materializer);

    assertEquals(Done.getInstance(), future.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustBeAbleToUseConflate() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).conflateWithSeed(new Function<String, String>() {
      @Override
      public String apply(String s) throws Exception {
        return s;
      }
    }, new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    });
    CompletionStage<String> future = Source.from(input).via(flow).runFold("", (aggr, in) -> aggr + in, materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("ABC", result);

    final Flow<String, String, NotUsed> flow2 = Flow.of(String.class).conflate((a, b) -> a + b);

    CompletionStage<String> future2 = Source.from(input).via(flow2).runFold("", (a, b) -> a + b, materializer);
    String result2 = future2.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("ABC", result2);
  }

  @Test
  public void mustBeAbleToUseBatch() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).batch(3L, new Function<String, String>() {
      @Override
      public String apply(String s) throws Exception {
        return s;
      }
    }, new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    });
    CompletionStage<String> future = Source.from(input).via(flow).runFold("", (aggr, in) -> aggr + in, materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("ABC", result);
  }

  @Test
  public void mustBeAbleToUseBatchWeighted() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).batchWeighted(3L, new Function<String, Object>() {
      @Override
      public Object apply(String s) throws Exception {
        return 1L;
      }
    }, new Function<String, String>() {
      @Override
      public String apply(String s) throws Exception {
        return s;
      }
    }, new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    });
    CompletionStage<String> future = Source.from(input).via(flow).runFold("", (aggr, in) -> aggr + in, materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("ABC", result);
  }

  @Test
  public void mustBeAbleToUseExpand() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).expand(in -> Stream.iterate(in, i -> i).iterator());
    final Sink<String, CompletionStage<String>> sink = Sink.<String>head();
    CompletionStage<String> future = Source.from(input).via(flow).runWith(sink, materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("A", result);
  }

  @Test
  public void mustBeAbleToUseMapAsync() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("a", "b", "c");
    final Flow<String, String, NotUsed> flow = Flow.of(String.class).mapAsync(4, elem -> CompletableFuture.completedFuture(elem.toUpperCase()));
    Source.from(input).via(flow).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);
    probe.expectMsgEquals("A");
    probe.expectMsgEquals("B");
    probe.expectMsgEquals("C");
  }

  @Test
  public void mustBeAbleToRecover() throws Exception {
    final TestPublisher.ManualProbe<Integer> publisherProbe = TestPublisher.manualProbe(true,system);
    final JavaTestKit probe = new JavaTestKit(system);

    final Source<Integer, NotUsed> source = Source.fromPublisher(publisherProbe);
    final Flow<Integer, Integer, NotUsed> flow = Flow.of(Integer.class).map(
            new Function<Integer, Integer>() {
              public Integer apply(Integer elem) {
                if (elem == 2) throw new RuntimeException("ex");
                else return elem;
              }
            })
            .recover(new JavaPartialFunction<Throwable, Integer>() {
              public Integer apply(Throwable elem, boolean isCheck) {
                if (isCheck) return null;
                return 0;
              }
            });

    final CompletionStage<Done> future =
        source.via(flow).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    final PublisherProbeSubscription<Integer> s = publisherProbe.expectSubscription();

    s.sendNext(0);
    probe.expectMsgEquals(0);
    s.sendNext(1);
    probe.expectMsgEquals(1);
    s.sendNext(2);
    probe.expectMsgEquals(0);
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToRecoverWithSource() throws Exception {
    final TestPublisher.ManualProbe<Integer> publisherProbe = TestPublisher.manualProbe(true,system);
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> recover = Arrays.asList(55, 0);

    final Source<Integer, NotUsed> source = Source.fromPublisher(publisherProbe);
    final Flow<Integer, Integer, NotUsed> flow = Flow.of(Integer.class).map(
            new Function<Integer, Integer>() {
              public Integer apply(Integer elem) {
                if (elem == 2) throw new RuntimeException("ex");
                else return elem;
              }
            })
            .recoverWith(new JavaPartialFunction<Throwable, Source<Integer, NotUsed>>() {
              public Source<Integer, NotUsed> apply(Throwable elem, boolean isCheck) {
                if (isCheck) return null;
                return Source.from(recover);
              }
            });

    final CompletionStage<Done> future =
            source.via(flow).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    final PublisherProbeSubscription<Integer> s = publisherProbe.expectSubscription();

    s.sendNext(0);
    probe.expectMsgEquals(0);
    s.sendNext(1);
    probe.expectMsgEquals(1);
    s.sendNext(2);
    probe.expectMsgEquals(55);
    probe.expectMsgEquals(0);
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToMaterializeIdentityWithJavaFlow() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");

    Flow<String,String,NotUsed> otherFlow = Flow.of(String.class);

    Flow<String,String,NotUsed> myFlow = Flow.of(String.class).via(otherFlow);
    Source.from(input).via(myFlow).runWith(Sink.foreach(new Procedure<String>() { // Scala Future
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgAllOf("A","B","C");
  }

  @Test
  public void mustBeAbleToMaterializeIdentityToJavaSink() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    Flow<String,String,NotUsed> otherFlow = Flow.of(String.class);

    Sink<String,NotUsed> sink = Flow.of(String.class).to(otherFlow.to(Sink.foreach(new Procedure<String>() { // Scala Future
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    })));

    Source.from(input).to(sink).run(materializer);
    probe.expectMsgAllOf("A","B","C");
  }

  @Test
  public void mustBeAbleToBroadcastEagerCancel() throws Exception {
    final Sink<String, NotUsed> sink = Sink.fromGraph(
      GraphDSL.create(new Function<GraphDSL.Builder<NotUsed>, SinkShape<String>>() {
        @Override
        public SinkShape<String> apply(Builder<NotUsed> b) throws Exception {
          final UniformFanOutShape<String, String> broadcast = b.add(Broadcast.<String>create(2, true));
          final SinkShape<String> out1 = b.add(Sink.<String>cancelled());
          final SinkShape<String> out2 = b.add(Sink.<String>ignore());
          b.from(broadcast.out(0)).to(out1);
          b.from(broadcast.out(1)).to(out2);
          return new SinkShape<String>(broadcast.in());
        }
      }));

    final JavaTestKit probe = new JavaTestKit(system);
    Source<String, ActorRef> source = Source.actorRef(1, OverflowStrategy.dropNew());
    final ActorRef actor = source.toMat(sink, Keep.<ActorRef, NotUsed>left()).run(materializer);
    probe.watch(actor);
    probe.expectTerminated(actor);
  }


  @Test
  public void mustBeAbleToUseZipWith() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    Source.from(input1).via(Flow.of(String.class).zipWith(Source.from(input2), new Function2<String, String, String>() {
      public String apply(String s1, String s2) {
        return s1 + "-" + s2;
      }
    })).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    probe.expectMsgEquals("A-D");
    probe.expectMsgEquals("B-E");
    probe.expectMsgEquals("C-F");
  }

  @Test
  public void mustBeAbleToUseZip2() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    Source.from(input1).via(Flow.of(String.class).zip(Source.from(input2)))
            .runForeach(new Procedure<Pair<String, String>>() {
              public void apply(Pair<String, String> elem) {
                probe.getRef().tell(elem, ActorRef.noSender());
              }
            }, materializer);

    probe.expectMsgEquals(new Pair<String,String>("A", "D"));
    probe.expectMsgEquals(new Pair<String,String>("B", "E"));
    probe.expectMsgEquals(new Pair<String,String>("C", "F"));
  }

  @Test
  public void mustBeAbleToUseMerge2() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    Source.from(input1).via(Flow.of(String.class).merge(Source.from(input2)))
            .runForeach(new Procedure<String>() {
              public void apply(String elem) {
                probe.getRef().tell(elem, ActorRef.noSender());
              }
            }, materializer);

    probe.expectMsgAllOf("A", "B", "C", "D", "E", "F");
  }

  @Test
  public void mustBeAbleToUseInitialTimeout() throws Throwable {
    try {
      try {
        Source.<Integer> maybe().via(Flow.of(Integer.class).initialTimeout(Duration.create(1, "second")))
            .runWith(Sink.<Integer> head(), materializer).toCompletableFuture().get(3, TimeUnit.SECONDS);
        fail("A TimeoutException was expected");
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    } catch (TimeoutException e) {
      // expected
    }
  }


  @Test
  public void mustBeAbleToUseCompletionTimeout() throws Throwable {
    try {
      try {
        Source.<Integer> maybe().via(Flow.of(Integer.class).completionTimeout(Duration.create(1, "second")))
            .runWith(Sink.<Integer> head(), materializer).toCompletableFuture().get(3, TimeUnit.SECONDS);
        fail("A TimeoutException was expected");
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    } catch (TimeoutException e) {
      // expected
    }
  }

  @Test
  public void mustBeAbleToUseIdleTimeout() throws Throwable {
    try {
      try {
        Source.<Integer> maybe().via(Flow.of(Integer.class).idleTimeout(Duration.create(1, "second")))
            .runWith(Sink.<Integer> head(), materializer).toCompletableFuture().get(3, TimeUnit.SECONDS);
        fail("A TimeoutException was expected");
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    } catch (TimeoutException e) {
      // expected
    }
  }

  @Test
  public void mustBeAbleToUseKeepAlive() throws Exception {
    Integer result =
        Source.<Integer>maybe()
            .via(Flow.of(Integer.class)
              .keepAlive(Duration.create(1, "second"), new Creator<Integer>() {
                public Integer create() {
                  return 0;
                }
              })
            )
            .takeWithin(Duration.create(1500, "milliseconds"))
            .runWith(Sink.<Integer>head(), materializer)
            .toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals((Object) 0, result);
  }

  @Test
  public void shouldBePossibleToCreateFromFunction() throws Exception {
    List<Integer> out = Source.range(0, 2).via(Flow.fromFunction((Integer x) -> x + 1))
      .runWith(Sink.seq(), materializer).toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(1, 2, 3), out);

  }

  public void mustSuitablyOverrideAttributeHandlingMethods() {
    @SuppressWarnings("unused")
    final Flow<Integer, Integer, NotUsed> f =
        Flow.of(Integer.class).withAttributes(Attributes.name("")).addAttributes(Attributes.asyncBoundary()).named("");
  }
}
