/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.dispatch.Foreach;
import akka.dispatch.Futures;
import akka.dispatch.OnSuccess;
import akka.japi.JavaPartialFunction;
import akka.japi.Pair;
import akka.japi.function.*;
import akka.japi.pf.PFBuilder;
import akka.stream.*;
import akka.stream.impl.ConstantFun;
import akka.stream.stage.*;
import akka.testkit.AkkaSpec;
import akka.stream.testkit.TestPublisher;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.util.Try;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static akka.stream.testkit.StreamTestKit.PublisherProbeSubscription;
import static akka.stream.testkit.TestPublisher.ManualProbe;
import static org.junit.Assert.*;

@SuppressWarnings("serial")
public class SourceTest extends StreamTest {
  public SourceTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("SourceTest",
    AkkaSpec.testConf());

  @Test
  public void mustBeAbleToUseSimpleOperators() {
    final JavaTestKit probe = new JavaTestKit(system);
    final String[] lookup = {"a", "b", "c", "d", "e", "f"};
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
    final Source<Integer, NotUsed> ints = Source.from(input);

    ints
      .drop(2)
      .take(3)
      .takeWithin(FiniteDuration.create(10, TimeUnit.SECONDS))
      .map(elem -> lookup[elem])
      .filter(elem -> !elem.equals("c"))
      .grouped(2)
      .mapConcat(elem -> elem)
      .groupedWithin(100, FiniteDuration.create(50, TimeUnit.MILLISECONDS))
      .mapConcat(elem -> elem)
      .runFold("", (acc, elem) -> acc + elem, materializer)
      .thenAccept(elem -> probe.getRef().tell(elem, ActorRef.noSender()));

    probe.expectMsgEquals("de");
  }

  @Test
  public void mustBeAbleToUseVoidTypeInForeach() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("a", "b", "c");
    Source<String, NotUsed> ints = Source.from(input);

    final CompletionStage<Done> completion = ints.runForeach(elem -> probe.getRef().tell(elem, ActorRef.noSender()), materializer);

    completion.thenAccept(elem -> probe.getRef().tell(String.valueOf(elem), ActorRef.noSender()));

    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
    probe.expectMsgEquals("Done");
  }

  @Ignore("StatefulStage to be converted to GraphStage when Java Api is available (#18817)") @Test
  public void mustBeAbleToUseTransform() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
    // duplicate each element, stop after 4 elements, and emit sum to the end
    Source.from(input).transform(new Creator<Stage<Integer, Integer>>() {
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
    }).runForeach(new Procedure<Integer>() {
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
    final Source<List<String>, NotUsed> source = Source
        .from(input)
        .groupBy(3, new Function<String, String>() {
          public String apply(String elem) {
            return elem.substring(0, 1);
          }
        })
        .grouped(10)
        .mergeSubstreams();

    final CompletionStage<List<List<String>>> future =
        source.grouped(10).runWith(Sink.<List<List<String>>> head(), materializer);
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
    final Source<List<String>, NotUsed> source = Source
        .from(input)
    	.splitWhen(new Predicate<String>() {
    	  public boolean test(String elem) {
            return elem.equals(".");
          }
        })
    	.grouped(10)
    	.concatSubstreams();

    final CompletionStage<List<List<String>>> future =
        source.grouped(10).runWith(Sink.<List<List<String>>> head(), materializer);
    final List<List<String>> result = future.toCompletableFuture().get(1, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(Arrays.asList("A", "B", "C"), Arrays.asList(".", "D"), Arrays.asList(".", "E", "F")), result);
  }

  @Test
  public void mustBeAbleToUseSplitAfter() throws Exception {
	final Iterable<String> input = Arrays.asList("A", "B", "C", ".", "D", ".", "E", "F");
    final Source<List<String>, NotUsed> source = Source
        .from(input)
	    .splitAfter(new Predicate<String>() {
	   	  public boolean test(String elem) {
	        return elem.equals(".");
	      }
	    })
	    .grouped(10)
	    .concatSubstreams();

    final CompletionStage<List<List<String>>> future =
        source.grouped(10).runWith(Sink.<List<List<String>>> head(), materializer);
    final List<List<String>> result = future.toCompletableFuture().get(1, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(Arrays.asList("A", "B", "C", "."), Arrays.asList("D", "."), Arrays.asList("E", "F")), result);
  }

  @Test
  public void mustBeAbleToUseConcat() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    final Source<String, NotUsed> in1 = Source.from(input1);
    final Source<String, NotUsed> in2 = Source.from(input2);

    in1.concat(in2).runForeach(new Procedure<String>() {
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

    in2.prepend(in1).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    List<Object> output = Arrays.asList(probe.receiveN(6));
    assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), output);
  }

  @Test
  public void mustBeAbleToUseCallableInput() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input1 = Arrays.asList(4, 3, 2, 1, 0);
    final Creator<Iterator<Integer>> input = new Creator<Iterator<Integer>>() {
      @Override
      public Iterator<Integer> create() {
        return input1.iterator();
      }
    };
    Source.fromIterator(input).runForeach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    List<Object> output = Arrays.asList(probe.receiveN(5));
    assertEquals(Arrays.asList(4, 3, 2, 1, 0), output);
    probe.expectNoMsg(FiniteDuration.create(500, TimeUnit.MILLISECONDS));
  }

  @Test
  public void mustBeAbleToUseOnCompleteSuccess() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C");

    Source.from(input).runWith(Sink.<String>onComplete(new Procedure<Try<Done>>() {
      @Override
      public void apply(Try<Done> param) throws Exception {
        probe.getRef().tell(param.get(), ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgClass(Done.class);
  }

  @Test
  public void mustBeAbleToUseOnCompleteError() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C");

    Source.from(input)
      .<String> map(in -> { throw new RuntimeException("simulated err"); })
      .runWith(Sink.<String>head(), materializer)
      .whenComplete((s, ex) -> {
        if (ex == null) {
          probe.getRef().tell("done", ActorRef.noSender());
        } else {
          probe.getRef().tell(ex.getMessage(), ActorRef.noSender());
        }
      });

    probe.expectMsgEquals("simulated err");
  }

  @Test
  public void mustBeAbleToUseToFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C");
    CompletionStage<String> future = Source.from(input).runWith(Sink.<String>head(), materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("A", result);
  }

  @Test
  public void mustBeAbleToUsePrefixAndTail() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6);
    CompletionStage<Pair<List<Integer>, Source<Integer, NotUsed>>> future = Source.from(input).prefixAndTail(3)
      .runWith(Sink.<Pair<List<Integer>, Source<Integer, NotUsed>>>head(), materializer);
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

    CompletionStage<List<Integer>> future = Source.from(mainInputs)
      .<Integer, NotUsed>flatMapConcat(ConstantFun.<Source<Integer,NotUsed>>javaIdentityFunction())
      .grouped(6)
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

    CompletionStage<List<Integer>> future = Source.from(mainInputs)
        .flatMapMerge(3, ConstantFun.<Source<Integer, NotUsed>>javaIdentityFunction()).grouped(60)
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
    final CompletionStage<List<String>> future = Source.from(input).buffer(2, OverflowStrategy.backpressure()).grouped(4)
      .runWith(Sink.<List<String>>head(), materializer);

    List<String> result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(input, result);
  }

  @Test
  public void mustBeAbleToUseConflate() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    CompletionStage<String> future = Source.from(input)
        .conflateWithSeed(s -> s, (aggr, in) -> aggr + in)
        .runFold("", (aggr, in) -> aggr + in, materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("ABC", result);


    final Flow<String, String, NotUsed> flow2 = Flow.of(String.class).conflate((a, b) -> a + b);

    CompletionStage<String> future2 = Source.from(input).conflate((String a, String b) -> a + b).runFold("", (a, b) -> a + b, materializer);
    String result2 = future2.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("ABC", result2);
  }

  @Test
  public void mustBeAbleToUseExpand() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    CompletionStage<String> future = Source.from(input).expand(in -> Stream.iterate(in, i -> i).iterator()).runWith(Sink.<String>head(), materializer);
    String result = future.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("A", result);
  }

  @Test
  public void mustProduceTicks() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    Source<String, Cancellable> tickSource = Source.tick(FiniteDuration.create(1, TimeUnit.SECONDS),
        FiniteDuration.create(500, TimeUnit.MILLISECONDS), "tick");
    @SuppressWarnings("unused")
    Cancellable cancellable = tickSource.to(Sink.foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    })).run(materializer);
    probe.expectNoMsg(FiniteDuration.create(600, TimeUnit.MILLISECONDS));
    probe.expectMsgEquals("tick");
    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));
    probe.expectMsgEquals("tick");
    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

  }

  @Test
  public void mustBeAbleToUseMapFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("a", "b", "c");
    Source.from(input)
      .mapAsync(4, elem -> CompletableFuture.completedFuture(elem.toUpperCase()))
      .runForeach(elem -> probe.getRef().tell(elem, ActorRef.noSender()), materializer);
    probe.expectMsgEquals("A");
    probe.expectMsgEquals("B");
    probe.expectMsgEquals("C");
  }

  @Test
  public void mustWorkFromFuture() throws Exception {
    final Iterable<String> input = Arrays.asList("A", "B", "C");
    CompletionStage<String> future1 = Source.from(input).runWith(Sink.<String>head(), materializer);
    CompletionStage<String> future2 = Source.fromCompletionStage(future1).runWith(Sink.<String>head(), materializer);
    String result = future2.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals("A", result);
  }

  @Test
  public void mustWorkFromRange() throws Exception {
    CompletionStage<List<Integer>> f = Source.range(0, 10).grouped(20).runWith(Sink.<List<Integer>> head(), materializer);
    final List<Integer> result = f.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(11, result.size());
    Integer counter = 0;
    for (Integer i: result)
      assertEquals(i, counter++);
  }

  @Test
  public void mustWorkFromRangeWithStep() throws Exception {
    CompletionStage<List<Integer>> f = Source.range(0, 10, 2).grouped(20).runWith(Sink.<List<Integer>> head(), materializer);
    final List<Integer> result = f.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(6, result.size());
    Integer counter = 0;
    for (Integer i: result) {
      assertEquals(i, counter);
      counter+=2;
    }
  }

  @Test
  public void mustRepeat() throws Exception {
    final CompletionStage<List<Integer>> f = Source.repeat(42).grouped(10000).runWith(Sink.<List<Integer>> head(), materializer);
    final List<Integer> result = f.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(result.size(), 10000);
    for (Integer i: result) assertEquals(i, (Integer) 42);
  }
  
  @Test
  public void mustBeAbleToUseQueue() throws Exception {
    final Pair<SourceQueueWithComplete<String>, CompletionStage<List<String>>> x = 
        Flow.of(String.class).runWith(
            Source.queue(2, OverflowStrategy.fail()),
            Sink.seq(), materializer);
    final SourceQueueWithComplete<String> source = x.first();
    final CompletionStage<List<String>> result = x.second();
    source.offer("hello");
    source.offer("world");
    source.complete();
    assertEquals(result.toCompletableFuture().get(3, TimeUnit.SECONDS),
        Arrays.asList("hello", "world"));
  }

  @Test
  public void mustBeAbleToUseActorRefSource() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, ActorRef> actorRefSource = Source.actorRef(10, OverflowStrategy.fail());
    final ActorRef ref = actorRefSource.to(Sink.foreach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    })).run(materializer);
    ref.tell(1, ActorRef.noSender());
    probe.expectMsgEquals(1);
    ref.tell(2, ActorRef.noSender());
    probe.expectMsgEquals(2);
  }

  @Test
  public void mustBeAbleToUseStatefulMaponcat() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
    final Source<Integer, NotUsed> ints = Source.from(input).statefulMapConcat(
            () -> {
              int[] state = new int[] {0};
              return (elem) -> {
                List<Integer> list = new ArrayList<>(Collections.nCopies(state[0], elem));
                state[0] = elem;
                return list;
              };
            });

    ints
      .runFold("", (acc, elem) -> acc + elem, materializer)
      .thenAccept(elem -> probe.getRef().tell(elem, ActorRef.noSender()));

    probe.expectMsgEquals("2334445555");
  }

  @Test
  public void mustBeAbleToUseIntersperse() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<String, NotUsed> source = Source.from(Arrays.asList("0", "1", "2", "3"))
                                                 .intersperse("[", ",", "]");

    final CompletionStage<Done> future =
        source.runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

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
    final Source<String, NotUsed> source = Source.from(Arrays.asList("0", "1", "2", "3"))
                                                 .intersperse(",");

    final CompletionStage<Done> future =
        Source.single(">> ").concat(source).runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

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
  public void mustBeAbleToUseDropWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3)).dropWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final CompletionStage<Done> future = source.runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToUseTakeWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3)).takeWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final CompletionStage<Done> future = source.runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);

    FiniteDuration duration = Duration.apply(200, TimeUnit.MILLISECONDS);

    probe.expectNoMsg(duration);
    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToRecover() throws Exception {
    final ManualProbe<Integer> publisherProbe = TestPublisher.manualProbe(true,system);
    final JavaTestKit probe = new JavaTestKit(system);

    final Source<Integer, NotUsed> source =
        Source.fromPublisher(publisherProbe)
          .map(elem -> {
                if (elem == 1) throw new RuntimeException("ex");
                else return elem;
              })
          .recover(new PFBuilder<Throwable, Integer>()
              .matchAny(ex -> 0)
              .build());

    final CompletionStage<Done> future = source.runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);
    final PublisherProbeSubscription<Integer> s = publisherProbe.expectSubscription();
    s.sendNext(0);
    probe.expectMsgEquals(0);
    s.sendNext(1);
    probe.expectMsgEquals(0);

    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToCombine() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, NotUsed> source1 = Source.from(Arrays.asList(0, 1));
    final Source<Integer, NotUsed> source2 = Source.from(Arrays.asList(2, 3));

    final Source<Integer, NotUsed> source = Source.combine(
        source1, source2, new ArrayList<Source<Integer, ?>>(),
        width -> Merge.<Integer> create(width));

    final CompletionStage<Done> future = source.runWith(Sink.foreach(elem -> probe.getRef().tell(elem, ActorRef.noSender())), materializer);

    probe.expectMsgAllOf(0, 1, 2, 3);

    future.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
  }

  @Test
  public void mustBeAbleToUseMerge() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    Source.from(input1).merge(Source.from(input2)).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    probe.expectMsgAllOf("A", "B", "C", "D", "E", "F");
  }

  @Test
  public void mustBeAbleToUseZipWith() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    Source.from(input1).zipWith(Source.from(input2),new Function2<String,String,String>(){
      public String apply(String s1,String s2){
         return s1+"-"+s2;
      }
    }).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    probe.expectMsgEquals("A-D");
    probe.expectMsgEquals("B-E");
    probe.expectMsgEquals("C-F");
  }

  @Test
  public void mustBeAbleToUseZip() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    Source.from(input1).zip(Source.from(input2)).runForeach(new Procedure<Pair<String,String>>() {
      public void apply(Pair<String,String> elem) {
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

    Source.from(input1).merge(Source.from(input2))
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
        Source.maybe().initialTimeout(Duration.create(1, "second")).runWith(Sink.head(), materializer)
            .toCompletableFuture().get(3, TimeUnit.SECONDS);
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
        Source.maybe().completionTimeout(Duration.create(1, "second")).runWith(Sink.head(), materializer)
            .toCompletableFuture().get(3, TimeUnit.SECONDS);
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
        Source.maybe().idleTimeout(Duration.create(1, "second")).runWith(Sink.head(), materializer)
            .toCompletableFuture().get(3, TimeUnit.SECONDS);
        fail("A TimeoutException was expected");
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    } catch (TimeoutException e) {
      // expected
    }
  }

  @Test
  public void mustBeAbleToUseIdleInject() throws Exception {
    Integer result =
        Source.maybe()
            .keepAlive(Duration.create(1, "second"), new Creator<Integer>() {
              public Integer create() {
                return 0;
              }
            })
            .takeWithin(Duration.create(1500, "milliseconds"))
            .runWith(Sink.<Integer>head(), materializer)
            .toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals((Object) 0, result);
  }

  public void mustSuitablyOverrideAttributeHandlingMethods() {
    @SuppressWarnings("unused")
    final Source<Integer, NotUsed> f =
        Source.single(42).withAttributes(Attributes.name("")).addAttributes(Attributes.asyncBoundary()).named("");
  }

  @Test
  public void mustBeAbleToUseThrottle() throws Exception {
    Integer result =
        Source.from(Arrays.asList(0, 1, 2))
            .throttle(10, FiniteDuration.create(1, TimeUnit.SECONDS), 10, ThrottleMode.shaping())
            .throttle(10, FiniteDuration.create(1, TimeUnit.SECONDS), 10, ThrottleMode.enforcing())
            .runWith(Sink.head(), materializer)
            .toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals((Object) 0, result);
  }
}
