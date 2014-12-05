package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.japi.Pair;
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
    final ZipWith<String, Integer, Pair<String,Integer>> zip = Zip.create();
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

}
