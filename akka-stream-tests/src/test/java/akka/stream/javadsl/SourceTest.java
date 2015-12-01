/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.dispatch.Foreach;
import akka.dispatch.Futures;
import akka.dispatch.OnSuccess;
import akka.japi.JavaPartialFunction;
import akka.japi.Pair;
import akka.japi.function.*;
import akka.stream.Graph;
import akka.stream.OverflowStrategy;
import akka.stream.StreamTest;
import akka.stream.UniformFanInShape;
import akka.stream.impl.ConstantFun;
import akka.stream.stage.*;
import akka.stream.testkit.AkkaSpec;
import akka.stream.testkit.TestPublisher;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import scala.util.Try;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static akka.stream.testkit.StreamTestKit.PublisherProbeSubscription;
import static akka.stream.testkit.TestPublisher.ManualProbe;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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
    final Source<Integer, ?> ints = Source.from(input);

    ints.drop(2).take(3).takeWithin(FiniteDuration.create(10, TimeUnit.SECONDS)).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return lookup[elem];
      }
    }).filter(new Predicate<String>() {
      public boolean test(String elem) {
        return !elem.equals("c");
      }
    }).grouped(2).mapConcat(new Function<java.util.List<String>, java.util.List<String>>() {
      public java.util.List<String> apply(java.util.List<String> elem) {
        return elem;
      }
    }).groupedWithin(100, FiniteDuration.create(50, TimeUnit.MILLISECONDS))
      .mapConcat(new Function<java.util.List<String>, java.util.List<String>>() {
        public java.util.List<String> apply(java.util.List<String> elem) {
          return elem;
        }
      }).runFold("", new Function2<String, String, String>() {
      public String apply(String acc, String elem) {
        return acc + elem;
      }
    }, materializer).foreach(new Foreach<String>() { // Scala Future
      public void each(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, system.dispatcher());

    probe.expectMsgEquals("de");
  }

  @Test
  public void mustBeAbleToUseVoidTypeInForeach() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("a", "b", "c");
    Source<String, ?> ints = Source.from(input);

    Future<BoxedUnit> completion = ints.runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    completion.onSuccess(new OnSuccess<BoxedUnit>() {
      @Override
      public void onSuccess(BoxedUnit elem) throws Throwable {
        probe.getRef().tell(String.valueOf(elem), ActorRef.noSender());
      }
    }, system.dispatcher());

    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
    probe.expectMsgEquals("()");
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

  @Test
  public void mustBeAbleToUseGroupBy() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("Aaa", "Abb", "Bcc", "Cdd", "Cee");
    Source.from(input).groupBy(new Function<String, String>() {
      public String apply(String elem) {
        return elem.substring(0, 1);
      }
    }).runForeach(new Procedure<Pair<String, Source<String, BoxedUnit>>>() {
      @Override
      public void apply(final Pair<String, Source<String, BoxedUnit>> pair) throws Exception {
        pair.second().runForeach(new Procedure<String>() {
          @Override
          public void apply(String elem) throws Exception {
            probe.getRef().tell(new Pair<String, String>(pair.first(), elem), ActorRef.noSender());
          }
        }, materializer);
      }
    }, materializer);

    Map<String, List<String>> grouped = new HashMap<String, List<String>>();
    for (Object o : probe.receiveN(5)) {
      @SuppressWarnings("unchecked")
      Pair<String, String> p = (Pair<String, String>) o;
      List<String> g = grouped.get(p.first());
      if (g == null) {
        g = new ArrayList<String>();
      }
      g.add(p.second());
      grouped.put(p.first(), g);
    }

    assertEquals(Arrays.asList("Aaa", "Abb"), grouped.get("A"));

  }

  @Test
  public void mustBeAbleToUseSplitWhen() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C", ".", "D", ".", "E", "F");
    Source.from(input).splitWhen(new Predicate<String>() {
      public boolean test(String elem) {
        return elem.equals(".");
      }
    }).runForeach(new Procedure<Source<String, BoxedUnit>>() {
      @Override
      public void apply(Source<String, BoxedUnit> subStream) throws Exception {
        subStream.filter(new Predicate<String>() {
          @Override
          public boolean test(String elem) {
            return !elem.equals(".");
          }
        }).grouped(10).runForeach(new Procedure<List<String>>() {
          @Override
          public void apply(List<String> chunk) throws Exception {
            probe.getRef().tell(chunk, ActorRef.noSender());
          }
        }, materializer);
      }
    }, materializer);

    for (Object o : probe.receiveN(3)) {
      @SuppressWarnings("unchecked")
      List<String> chunk = (List<String>) o;
      if (chunk.get(0).equals("A")) {
        assertEquals(Arrays.asList("A", "B", "C"), chunk);
      } else if (chunk.get(0).equals("D")) {
        assertEquals(Arrays.asList("D"), chunk);
      } else if (chunk.get(0).equals("E")) {
        assertEquals(Arrays.asList("E", "F"), chunk);
      } else {
        assertEquals("[A, B, C] or [D] or [E, F]", chunk);
      }
    }

  }

  @Test
  public void mustBeAbleToUseConcat() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    final Source<String, ?> in1 = Source.from(input1);
    final Source<String, ?> in2 = Source.from(input2);

    in1.concat(in2).runForeach(new Procedure<String>() {
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

    Source.from(input).runWith(Sink.<String>onComplete(new Procedure<Try<BoxedUnit>>() {
      @Override
      public void apply(Try<BoxedUnit> param) throws Exception {
        probe.getRef().tell(param.get(), ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgClass(BoxedUnit.class);
  }

  @Test
  public void mustBeAbleToUseOnCompleteError() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C");

    Source.from(input).map(new Function<String, String>() {
      public String apply(String arg0) throws Exception {
        throw new RuntimeException("simulated err");
      }
    }).runWith(Sink.<String>head(), materializer).onComplete(new OnSuccess<Try<String>>() {
      @Override
      public void onSuccess(Try<String> e) throws Throwable {
        if (e == null) {
          probe.getRef().tell("done", ActorRef.noSender());
        } else {
          probe.getRef().tell(e.failed().get().getMessage(), ActorRef.noSender());
        }
      }
    }, system.dispatcher());

    probe.expectMsgEquals("simulated err");
  }

  @Test
  public void mustBeAbleToUseToFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C");
    Future<String> future = Source.from(input).runWith(Sink.<String>head(), materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("A", result);
  }

  @Test
  public void mustBeAbleToUsePrefixAndTail() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6);
    Future<Pair<List<Integer>, Source<Integer, BoxedUnit>>> future = Source.from(input).prefixAndTail(3)
      .runWith(Sink.<Pair<List<Integer>, Source<Integer, BoxedUnit>>>head(), materializer);
    Pair<List<Integer>, Source<Integer, BoxedUnit>> result = Await.result(future,
      probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(Arrays.asList(1, 2, 3), result.first());

    Future<List<Integer>> tailFuture = result.second().grouped(4).runWith(Sink.<List<Integer>>head(), materializer);
    List<Integer> tailResult = Await.result(tailFuture, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(Arrays.asList(4, 5, 6), tailResult);
  }

  @Test
  public void mustBeAbleToUseConcatAllWithSources() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input1 = Arrays.asList(1, 2, 3);
    final Iterable<Integer> input2 = Arrays.asList(4, 5);

    final List<Source<Integer, BoxedUnit>> mainInputs = new ArrayList<Source<Integer,BoxedUnit>>();
    mainInputs.add(Source.from(input1));
    mainInputs.add(Source.from(input2));

    Future<List<Integer>> future = Source.from(mainInputs)
      .<Integer, BoxedUnit>flatMapConcat(ConstantFun.<Source<Integer,BoxedUnit>>javaIdentityFunction())
      .grouped(6)
      .runWith(Sink.<List<Integer>>head(), materializer);

    List<Integer> result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));

    assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
  }

  @Test
  public void mustBeAbleToUseFlatMapMerge() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input1 = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    final Iterable<Integer> input2 = Arrays.asList(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
    final Iterable<Integer> input3 = Arrays.asList(20, 21, 22, 23, 24, 25, 26, 27, 28, 29);
    final Iterable<Integer> input4 = Arrays.asList(30, 31, 32, 33, 34, 35, 36, 37, 38, 39);

    final List<Source<Integer, BoxedUnit>> mainInputs = new ArrayList<Source<Integer,BoxedUnit>>();
    mainInputs.add(Source.from(input1));
    mainInputs.add(Source.from(input2));
    mainInputs.add(Source.from(input3));
    mainInputs.add(Source.from(input4));

    Future<List<Integer>> future = Source.from(mainInputs)
        .flatMapMerge(3, ConstantFun.<Source<Integer, BoxedUnit>>javaIdentityFunction()).grouped(60)
        .runWith(Sink.<List<Integer>>head(), materializer);

    List<Integer> result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
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
    Future<List<String>> future = Source.from(input).buffer(2, OverflowStrategy.backpressure()).grouped(4)
      .runWith(Sink.<List<String>>head(), materializer);

    List<String> result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(input, result);
  }

  @Test
  public void mustBeAbleToUseConflate() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    Future<String> future = Source.from(input).conflate(new Function<String, String>() {
      @Override
      public String apply(String s) throws Exception {
        return s;
      }
    }, new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    }).runFold("", new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    }, materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("ABC", result);
  }

  @Test
  public void mustBeAbleToUseExpand() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    Future<String> future = Source.from(input).expand(new Function<String, String>() {
      @Override
      public String apply(String in) throws Exception {
        return in;
      }
    }, new Function<String, Pair<String, String>>() {
      @Override
      public Pair<String, String> apply(String in) throws Exception {
        return new Pair<String, String>(in, in);
      }
    }).runWith(Sink.<String>head(), materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("A", result);
  }

  @Test
  public void mustProduceTicks() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    Source<String, Cancellable> tickSource = Source.tick(FiniteDuration.create(1, TimeUnit.SECONDS),
        FiniteDuration.create(500, TimeUnit.MILLISECONDS), "tick");
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
    Source.from(input).mapAsync(4, new Function<String, Future<String>>() {
      public Future<String> apply(String elem) {
        return Futures.successful(elem.toUpperCase());
      }
    }).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);
    probe.expectMsgEquals("A");
    probe.expectMsgEquals("B");
    probe.expectMsgEquals("C");
  }

  @Test
  public void mustWorkFromFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C");
    Future<String> future1 = Source.from(input).runWith(Sink.<String>head(), materializer);
    Future<String> future2 = Source.from(future1).runWith(Sink.<String>head(), materializer);
    String result = Await.result(future2, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("A", result);
  }

  @Test
  public void mustRepeat() throws Exception {
    final Future<List<Integer>> f = Source.repeat(42).grouped(10000).runWith(Sink.<List<Integer>> head(), materializer);
    final List<Integer> result = Await.result(f, FiniteDuration.create(3, TimeUnit.SECONDS));
    assertEquals(result.size(), 10000);
    for (Integer i: result) assertEquals(i, (Integer) 42);
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
  public void mustBeAbleToUseDropWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, ?> source = Source.from(Arrays.asList(0, 1, 2, 3)).dropWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final Future<BoxedUnit> future = source.runWith(Sink.foreach(new Procedure<Integer>() { // Scala Future
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    Await.ready(future, Duration.apply(200, TimeUnit.MILLISECONDS));
  }

  @Test
  public void mustBeAbleToUseTakeWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, ?> source = Source.from(Arrays.asList(0, 1, 2, 3)).takeWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final Future<BoxedUnit> future = source.runWith(Sink.foreach(new Procedure<Integer>() { // Scala Future
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);

    FiniteDuration duration = Duration.apply(200, TimeUnit.MILLISECONDS);

    probe.expectNoMsg(duration);
    Await.ready(future, duration);
  }

  @Test
  public void mustBeAbleToRecover() throws Exception {
    final ManualProbe<Integer> publisherProbe = TestPublisher.manualProbe(true,system);
    final JavaTestKit probe = new JavaTestKit(system);

    final Source<Integer, ?> source = Source.from(publisherProbe).map(
            new Function<Integer, Integer>() {
              public Integer apply(Integer elem) {
                if (elem == 1) throw new RuntimeException("ex");
                else return elem;
              }
            })
            .recover(new JavaPartialFunction<Throwable, Integer>() {
              public Integer apply(Throwable elem, boolean isCheck) {
                if (isCheck) return null;
                return 0;
              }
            });

    final Future<BoxedUnit> future = source.runWith(Sink.foreach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);
    final PublisherProbeSubscription<Integer> s = publisherProbe.expectSubscription();
    s.sendNext(0);
    probe.expectMsgEquals(0);
    s.sendNext(1);
    probe.expectMsgEquals(0);

    Await.ready(future, Duration.apply(200, TimeUnit.MILLISECONDS));
  }

  @Test
  public void mustBeAbleToCombine() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, ?> source1 = Source.from(Arrays.asList(0, 1));
    final Source<Integer, ?> source2 = Source.from(Arrays.asList(2, 3));

    final Source<Integer, ?> source = Source.combine(source1, source2, new ArrayList<Source<Integer, ?>>(),
            new Function<Integer, Graph<UniformFanInShape<Integer, Integer>, BoxedUnit>>() {
              public Graph<UniformFanInShape<Integer, Integer>, BoxedUnit> apply(Integer elem) {
                return Merge.create(elem);
              }
            });

    final Future<BoxedUnit> future = source.runWith(Sink.foreach(new Procedure<Integer>() { // Scala Future
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgAllOf(0, 1, 2, 3);

    Await.ready(future, Duration.apply(200, TimeUnit.MILLISECONDS));
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
  public void mustBeAbleToUseInitialTimeout() throws Exception {
    try {
      Await.result(
          Source.maybe().initialTimeout(Duration.create(1, "second")).runWith(Sink.head(), materializer),
          Duration.create(3, "second")
      );
      fail("A TimeoutException was expected");
    } catch(TimeoutException e) {
      // expected
    }
  }


  @Test
  public void mustBeAbleToUseCompletionTimeout() throws Exception {
    try {
      Await.result(
          Source.maybe().completionTimeout(Duration.create(1, "second")).runWith(Sink.head(), materializer),
          Duration.create(3, "second")
      );
      fail("A TimeoutException was expected");
    } catch(TimeoutException e) {
      // expected
    }
  }

  @Test
  public void mustBeAbleToUseIdleTimeout() throws Exception {
    try {
      Await.result(
          Source.maybe().idleTimeout(Duration.create(1, "second")).runWith(Sink.head(), materializer),
          Duration.create(3, "second")
      );
      fail("A TimeoutException was expected");
    } catch(TimeoutException e) {
      // expected
    }
  }

  @Test
  public void mustBeAbleToUseIdleInject() throws Exception {
    Integer result = Await.result(
        Source.maybe()
            .keepAlive(Duration.create(1, "second"), new Creator<Integer>() {
              public Integer create() {
                return 0;
              }
            })
            .takeWithin(Duration.create(1500, "milliseconds"))
            .runWith(Sink.<Integer>head(), materializer),
        Duration.create(3, "second")
    );

    assertEquals((Object) 0, result);
  }

}
