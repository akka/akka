package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.dispatch.Foreach;
import akka.dispatch.Futures;
import akka.dispatch.OnSuccess;
import akka.japi.Pair;
import akka.stream.OverflowStrategy;
import akka.stream.StreamTest;
import akka.stream.stage.*;
import akka.stream.javadsl.japi.*;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import scala.util.Try;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;

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
    final Source<Integer> ints = Source.from(input);

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
        }).fold("", new Function2<String, String, String>() {
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
    Source<String> ints = Source.from(input);

    Future<BoxedUnit> completion = ints.foreach(new Procedure<String>() {
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

  @Test
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
              public Directive onPush(Integer element, Context<Integer> ctx) {
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
    }).foreach(new Procedure<Integer>() {
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
    }).foreach(new Procedure<Pair<String, Source<String>>>() {
      @Override
      public void apply(final Pair<String, Source<String>> pair) throws Exception {
        pair.second().foreach(new Procedure<String>() {
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
    }).foreach(new Procedure<Source<String>>() {
      @Override
      public void apply(Source<String> subStream) throws Exception {
        subStream.filter(new Predicate<String>() {
          @Override
          public boolean test(String elem) {
            return !elem.equals(".");
          }
        }).grouped(10).foreach(new Procedure<List<String>>() {
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

  public <T> Creator<Stage<T, T>> op() {
    return new akka.stream.javadsl.japi.Creator<Stage<T, T>>() {
      @Override
      public PushPullStage<T, T> create() throws Exception {
        return new PushPullStage<T, T>() {  
          @Override
          public Directive onPush(T element, Context<T> ctx) {
            return ctx.push(element);
          }
          
          @Override
          public Directive onPull(Context<T> ctx) {
            return ctx.pull();
          }
        };
      }
    };
  }

  @Test
  public void mustBeAbleToUseMerge() throws Exception {
    final Flow<String, String> f1 = Flow.of(String.class).section(OperationAttributes.name("f1"), new Function<Flow<String, String>, Flow<String, String>>() {
      @Override
      public Flow<String, String> apply(Flow<String, String> flow) {
        return flow.transform(FlowTest.this.<String>op());
      }
    });
    final Flow<String, String> f2 = Flow.of(String.class).section(OperationAttributes.name("f2"), new Function<Flow<String, String>, Flow<String, String>>() {
      @Override
      public Flow<String, String> apply(Flow<String, String> flow) {
        return flow.transform(FlowTest.this.<String>op());
      }
    });
    final Flow<String, String> f3 = Flow.of(String.class).section(OperationAttributes.name("f3"), new Function<Flow<String, String>, Flow<String, String>>() {
      @Override
      public Flow<String, String> apply(Flow<String, String> flow) {
        return flow.transform(FlowTest.this.<String>op());
      }
    });

    final Source<String> in1 = Source.from(Arrays.asList("a", "b", "c"));
    final Source<String> in2 = Source.from(Arrays.asList("d", "e", "f"));

    final KeyedSink<String, Publisher<String>> publisher = Sink.publisher();

    // this is red in intellij, but actually valid, scalac generates bridge methods for Java, so inference *works*
    final Merge<String> merge = Merge.<String> create();
    MaterializedMap m = FlowGraph.builder().addEdge(in1, f1, merge).addEdge(in2, f2, merge)
        .addEdge(merge, f3, publisher).build().run(materializer);

    // collecting
    final Publisher<String> pub = m.get(publisher);
    final Future<List<String>> all = Source.from(pub).grouped(100).runWith(Sink.<List<String>>head(), materializer);

    final List<String> result = Await.result(all, Duration.apply(200, TimeUnit.MILLISECONDS));
    assertEquals(new HashSet<Object>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<String>(result));
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> input2 = Arrays.asList(1, 2, 3);

    final Source<String> in1 = Source.from(input1);
    final Source<Integer> in2 = Source.from(input2);
    final Zip<String, Integer> zip = Zip.create();
    final KeyedSink<Pair<String, Integer>, Future<BoxedUnit>> out = Sink
        .foreach(new Procedure<Pair<String, Integer>>() {
          @Override
          public void apply(Pair<String, Integer> param) throws Exception {
            probe.getRef().tell(param, ActorRef.noSender());
          }
        });

    FlowGraph.builder().addEdge(in1, zip.left()).addEdge(in2, zip.right()).addEdge(zip.out(), out).run(materializer);

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

    final Source<Pair<String, Integer>> in = Source.from(input);
    final Unzip<String, Integer> unzip = Unzip.create();

    final KeyedSink<String, Future<BoxedUnit>> out1 = Sink.foreach(new Procedure<String>() {
      @Override
      public void apply(String param) throws Exception {
        probe1.getRef().tell(param, ActorRef.noSender());
      }
    });
    final KeyedSink<Integer, Future<BoxedUnit>> out2 = Sink.foreach(new Procedure<Integer>() {
      @Override
      public void apply(Integer param) throws Exception {
        probe2.getRef().tell(param, ActorRef.noSender());
      }
    });

    FlowGraph.builder().addEdge(in, unzip.in()).addEdge(unzip.left(), out1).addEdge(unzip.right(), out2)
        .run(materializer);

    List<Object> output1 = Arrays.asList(probe1.receiveN(3));
    List<Object> output2 = Arrays.asList(probe2.receiveN(3));
    assertEquals(expected1, output1);
    assertEquals(expected2, output2);
  }

  @Test
  public void mustBeAbleToUseConcat() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    final Source<String> in1 = Source.from(input1);
    final Source<String> in2 = Source.from(input2);

    in1.concat(in2).foreach(new Procedure<String>() {
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
    final Iterable<Integer> input1 = Arrays.asList(4,3,2,1,0);
    final akka.stream.javadsl.japi.Creator<Iterator<Integer>> input = new akka.stream.javadsl.japi.Creator<Iterator<Integer>>() {
      @Override
      public Iterator<Integer> create() {
        return input1.iterator();
      }
    };
    Source.from(input).foreach(new Procedure<Integer>() {
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

    Source.from(input).runWith(Sink.<String> onComplete(new Procedure<BoxedUnit>() {
      @Override
      public void apply(BoxedUnit param) throws Exception {
        probe.getRef().tell(param, ActorRef.noSender());
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
    Future<Pair<List<Integer>, Source<Integer>>> future = Source.from(input).prefixAndTail(3)
        .runWith(Sink.<Pair<List<Integer>, Source<Integer>>>head(), materializer);
    Pair<List<Integer>, Source<Integer>> result = Await.result(future,
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

    final List<Source<Integer>> mainInputs = Arrays.asList(Source.from(input1), Source.from(input2));

    Future<List<Integer>> future = Source.from(mainInputs)
        .flatten(akka.stream.javadsl.FlattenStrategy.<Integer> concat()).grouped(6)
        .runWith(Sink.<List<Integer>>head(), materializer);

    List<Integer> result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));

    assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
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
    //    final List<String> input = Arrays.asList("A", "B", "C"); // test was fleaky // TODO FIXME, test was fleaky!
    final List<String> input = Arrays.asList("C");
    Future<String> future = Source.from(input).conflate(new Function<String, String>() {
      @Override
      public String apply(String s) throws Exception {
        return s;
      }
    }, new Function2<String, String, String>() {
      @Override
      public String apply(String in, String aggr) throws Exception {
        return in;
      }
    }).fold("", new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return in;
      }
    }, materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("C", result);
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
    final Callable<String> tick = new Callable<String>() {
      private int count = 1;

      @Override
      public String call() {
        return "tick-" + (count++);
      }
    };
    Source.from(FiniteDuration.create(1, TimeUnit.SECONDS), FiniteDuration.create(500, TimeUnit.MILLISECONDS), tick)
        .foreach(new Procedure<String>() {
          public void apply(String elem) {
            probe.getRef().tell(elem, ActorRef.noSender());
          }
        }, materializer);
    probe.expectNoMsg(FiniteDuration.create(600, TimeUnit.MILLISECONDS));
    probe.expectMsgEquals("tick-1");
    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));
    probe.expectMsgEquals("tick-2");
    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

  }

  @Test
  public void mustBeAbleToUseMapFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("a", "b", "c");
    Source.from(input).mapAsync(new Function<String, Future<String>>() {
      public Future<String> apply(String elem) {
        return Futures.successful(elem.toUpperCase());
      }
    }).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);
    probe.expectMsgEquals("A");
    probe.expectMsgEquals("B");
    probe.expectMsgEquals("C");
  }

}
