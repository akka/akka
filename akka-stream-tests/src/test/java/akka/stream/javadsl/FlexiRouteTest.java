/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import java.util.Arrays;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorSystem;
import akka.stream.FlowMaterializer;
import akka.stream.testkit.AkkaSpec;
import akka.stream.javadsl.FlexiRoute;
import akka.japi.Pair;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

public class FlexiRouteTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlexiRouteTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final FlowMaterializer materializer = FlowMaterializer.create(system);

  final Source<String> in = Source.from(Arrays.asList("a", "b", "c", "d", "e"));

  final KeyedSink<List<String>, Future<List<String>>> out1 = Sink.<List<String>>future();
  final KeyedSink<List<String>, Future<List<String>>> out2 = Sink.<List<String>>future();

  @Test
  public void mustBuildSimpleFairRoute() throws Exception {
    Fair<String> route = new Fair<String>();

    MaterializedMap m = FlowGraph.builder().addEdge(in, route.in())
        .addEdge(route.output1(), Flow.of(String.class).grouped(100), out1)
        .addEdge(route.output2(), Flow.of(String.class).grouped(100), out2).run(materializer);

    final List<String> result1 = Await.result(m.get(out1), Duration.apply(3, TimeUnit.SECONDS));
    final List<String> result2 = Await.result(m.get(out2), Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(Arrays.asList("a", "c", "e"), result1);
    assertEquals(Arrays.asList("b", "d"), result2);
  }

  @Test
  public void mustBuildSimpleRoundRobinRoute() throws Exception {
    StrictRoundRobin<String> route = new StrictRoundRobin<String>();

    MaterializedMap m = FlowGraph.builder().addEdge(in, route.in())
        .addEdge(route.output1(), Flow.of(String.class).grouped(100), out1)
        .addEdge(route.output2(), Flow.of(String.class).grouped(100), out2).run(materializer);

    final List<String> result1 = Await.result(m.get(out1), Duration.apply(3, TimeUnit.SECONDS));
    final List<String> result2 = Await.result(m.get(out2), Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(Arrays.asList("a", "c", "e"), result1);
    assertEquals(Arrays.asList("b", "d"), result2);
  }

  @Test
  public void mustBuildSimpleUnzip() throws Exception {
    Unzip<Integer, String> unzip = new Unzip<Integer, String>();

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Source<Pair<Integer, String>> input = Source.from(Arrays.<Pair<Integer, String>>asList(new Pair(1, "A"), new Pair(
        2, "B"), new Pair(3, "C"), new Pair(4, "D")));

    final KeyedSink<List<Integer>, Future<List<Integer>>> outA = Sink.<List<Integer>>future();
    final KeyedSink<List<String>, Future<List<String>>> outB = Sink.<List<String>>future();

    MaterializedMap m = FlowGraph.builder().addEdge(input, unzip.in())
        .addEdge(unzip.outputA, Flow.of(Integer.class).grouped(100), outA)
        .addEdge(unzip.outputB, Flow.of(String.class).grouped(100), outB).run(materializer);

    final List<Integer> result1 = Await.result(m.get(outA), Duration.apply(3, TimeUnit.SECONDS));
    final List<String> result2 = Await.result(m.get(outB), Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(Arrays.asList(1, 2, 3, 4), result1);
    assertEquals(Arrays.asList("A", "B", "C", "D"), result2);
  }

  /**
   * This is fair in that sense that after enqueueing to an output it yields to
   * other output if they are have requested elements. Or in other words, if all
   * outputs have demand available at the same time then in finite steps all
   * elements are enqueued to them.
   */
  static public class Fair<T> extends FlexiRoute<T, T> {

    private final OutputPort<T, T> output1 = createOutputPort();
    private final OutputPort<T, T> output2 = createOutputPort();

    public Fair() {
      super("fairRoute");
    }

    public OutputPort<T, T> output1() {
      return output1;
    }

    public OutputPort<T, T> output2() {
      return output2;
    }

    @Override
    public RouteLogic<T, T> createRouteLogic() {
      return new RouteLogic<T, T>() {
        @Override
        public List<OutputHandle> outputHandles(int outputCount) {
          return Arrays.asList(output1.handle(), output2.handle());
        }

        private State<T, T> emitToAnyWithDemand = new State<T, T>(demandFromAny(output1, output2)) {
          @Override
          public State<T, T> onInput(RouteLogicContext<T, T> ctx, OutputHandle preferred, T element) {
            ctx.emit(preferred, element);
            return sameState();
          }
        };

        @Override
        public State<T, T> initialState() {
          return new State<T, T>(demandFromAny(output1, output2)) {
            @Override
            public State<T, T> onInput(RouteLogicContext<T, T> ctx, OutputHandle preferred, T element) {
              ctx.emit(preferred, element);
              return emitToAnyWithDemand;
            }
          };
        }
      };
    }
  }

  /**
   * It never skips an output while cycling but waits on it instead (closed
   * outputs are skipped though). The fair route above is a non-strict
   * round-robin (skips currently unavailable outputs).
   */
  static public class StrictRoundRobin<T> extends FlexiRoute<T, T> {

    private final OutputPort<T, T> output1 = createOutputPort();
    private final OutputPort<T, T> output2 = createOutputPort();

    public StrictRoundRobin() {
      super("roundRobinRoute");
    }

    public OutputPort<T, T> output1() {
      return output1;
    }

    public OutputPort<T, T> output2() {
      return output2;
    }

    @Override
    public RouteLogic<T, T> createRouteLogic() {
      return new RouteLogic<T, T>() {
        @Override
        public List<OutputHandle> outputHandles(int outputCount) {
          return Arrays.asList(output1.handle(), output2.handle());
        }

        private State<T, T> toOutput1 = new State<T, T>(demandFrom(output1)) {
          @Override
          public State<T, T> onInput(RouteLogicContext<T, T> ctx, OutputHandle preferred, T element) {
            ctx.emit(output1, element);
            return toOutput2;
          }
        };

        private State<T, T> toOutput2 = new State<T, T>(demandFrom(output2)) {
          @Override
          public State<T, T> onInput(RouteLogicContext<T, T> ctx, OutputHandle preferred, T element) {
            ctx.emit(output2, element);
            return toOutput1;
          }
        };

        @Override
        public State<T, T> initialState() {
          return toOutput1;
        }

      };
    }
  }

  static public class Unzip<A, B> extends FlexiRoute<Pair<A, B>, Object> {

    public final OutputPort<Pair<A, B>, A> outputA = createOutputPort();
    public final OutputPort<Pair<A, B>, B> outputB = createOutputPort();

    public Unzip() {
      super("unzip");
    }

    @Override
    public RouteLogic<Pair<A, B>, Object> createRouteLogic() {
      return new RouteLogic<Pair<A, B>, Object>() {

        @Override
        public List<OutputHandle> outputHandles(int outputCount) {
          if (outputCount != 2)
            throw new IllegalArgumentException("Unzip must have two connected outputs, was " + outputCount);
          return Arrays.asList(outputA.handle(), outputB.handle());
        }

        @Override
        public State<Pair<A, B>, Object> initialState() {
          return new State<Pair<A, B>, Object>(demandFromAll(outputA, outputB)) {
            @Override
            public State<Pair<A, B>, Object> onInput(RouteLogicContext<Pair<A, B>, Object> ctx, OutputHandle preferred,
                Pair<A, B> element) {
              ctx.emit(outputA, element.first());
              ctx.emit(outputB, element.second());
              return sameState();
            }
          };
        }

        @Override
        public CompletionHandling<Pair<A, B>> initialCompletionHandling() {
          return eagerClose();
        }

      };
    }
  }

}
