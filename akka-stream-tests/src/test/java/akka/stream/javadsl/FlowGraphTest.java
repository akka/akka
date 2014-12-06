/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.japi.*;
import akka.stream.StreamTest;
import akka.stream.javadsl.japi.Creator;
import akka.stream.javadsl.japi.Function;
import akka.stream.javadsl.japi.Function2;
import akka.stream.javadsl.japi.Procedure;
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
    final JavaTestKit probe = new JavaTestKit(system);
    final Flow<String, String> f1 = Flow.of(String.class).section(OperationAttributes.name("f1"), new Function<Flow<String, String>, Flow<String, String>>() {
      @Override
      public Flow<String, String> apply(Flow<String, String> flow) {
        return flow.transform(FlowGraphTest.this.<String>op());
      }
    });
    final Flow<String, String> f2 = Flow.of(String.class).section(OperationAttributes.name("f2"), new Function<Flow<String, String>, Flow<String, String>>() {
      @Override
      public Flow<String, String> apply(Flow<String, String> flow) {
        return flow.transform(FlowGraphTest.this.<String>op());
      }
    });
    final Flow<String, String> f3 = Flow.of(String.class).section(OperationAttributes.name("f3"), new Function<Flow<String, String>, Flow<String, String>>() {
      @Override
      public Flow<String, String> apply(Flow<String, String> flow) {
        return flow.transform(FlowGraphTest.this.<String>op());
      }
    });

    final Source<String> in1 = Source.from(Arrays.asList("a", "b", "c"));
    final Source<String> in2 = Source.from(Arrays.asList("d", "e", "f"));

    final KeyedSink<String, Publisher<String>> publisher = Sink.publisher();

    final Merge<String> merge = Merge.<String>create();
    MaterializedMap m = FlowGraph.builder().addEdge(in1, f1, merge).addEdge(in2, f2, merge)
      .addEdge(merge, f3, publisher).build().run(materializer);

    // collecting
    final Publisher<String> pub = m.get(publisher);
    final Future<List<String>> all = Source.from(pub).grouped(100).runWith(Sink.<List<String>>head(), materializer);

    final List<String> result = Await.result(all, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(new HashSet<Object>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<String>(result));
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> input2 = Arrays.asList(1, 2, 3);

    final Source<String> in1 = Source.from(input1);
    final Source<Integer> in2 = Source.from(input2);
    final Zip2With<String, Integer, Pair<String,Integer>> zip = Zip.create();
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
  public void mustBeAbleToUseZipWith() throws Exception {
    final Source<Integer> in1 = Source.single(1);
    final Source<Integer> in2 = Source.single(10);

    final Zip2With<Integer, Integer, Integer> sumZip = ZipWith.create(

      new Function2<Integer, Integer, Integer>() {
        @Override public Integer apply(Integer l, Integer r) throws Exception {
          return l + r;
      }
    });

    final KeyedSink<Integer, Future<Integer>> out = Sink.head();

    MaterializedMap mat = FlowGraph.builder()
      .addEdge(in1, sumZip.left())
      .addEdge(in2, sumZip.right())
      .addEdge(sumZip.out(), out)
      .run(materializer);

    final Integer result = Await.result(mat.get(out), Duration.create(300, TimeUnit.MILLISECONDS));
    assertEquals(11, (int) result);
  }

  @Test
  public void mustBeAbleToUseZip4With() throws Exception {
    final Source<Integer> in1 = Source.single(1);
    final Source<Integer> in2 = Source.single(10);
    final Source<Integer> in3 = Source.single(100);
    final Source<Integer> in4 = Source.single(1000);

    Function<ZipWith.Zip4WithInputs<Integer, Integer, Integer, Integer>, Integer> sum4 = new Function<ZipWith.Zip4WithInputs<Integer, Integer, Integer, Integer>, Integer>() {
      @Override
      public Integer apply(ZipWith.Zip4WithInputs<Integer, Integer, Integer, Integer> inputs) throws Exception {
        return inputs.t1() + inputs.t2() + inputs.t3() + inputs.t4();
      }
    };

    Zip4With<Integer, Integer, Integer, Integer, Integer> sum4Zip = ZipWith.create(sum4);

    final KeyedSink<Integer, Future<Integer>> out = Sink.head();

    MaterializedMap mat = FlowGraph.builder()
      .addEdge(in1, sum4Zip.input1())
      .addEdge(in2, sum4Zip.input2())
      .addEdge(in3, sum4Zip.input3())
      .addEdge(in4, sum4Zip.input4())
      .addEdge(sum4Zip.out(), out)
      .run(materializer);

    final Integer result = Await.result(mat.get(out), Duration.create(300, TimeUnit.MILLISECONDS));
    assertEquals(1111, (int) result);
  }

}
