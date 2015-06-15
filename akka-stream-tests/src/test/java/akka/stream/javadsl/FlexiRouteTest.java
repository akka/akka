/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;

import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.testkit.AkkaSpec;
import akka.stream.javadsl.FlexiRoute;
import akka.stream.javadsl.FlowGraph.Builder;
import akka.japi.function.Procedure3;
import akka.japi.Pair;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

public class FlexiRouteTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlexiRouteTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final ActorMaterializer materializer = ActorMaterializer.create(system);

  final Source<String, BoxedUnit> in = Source.from(Arrays.asList("a", "b", "c", "d", "e"));

  final Sink<List<String>, Future<List<String>>> out1 = Sink.<List<String>>head();
  final Sink<List<String>, Future<List<String>>> out2 = Sink.<List<String>>head();

  @Test
  public void mustBuildSimpleFairRoute() throws Exception {
    final Pair<Future<List<String>>, Future<List<String>>> result = FlowGraph
        .factory()
        .closed(
            out1,
            out2,
            Keep.<Future<List<String>>, Future<List<String>>> both(),
            new Procedure3<Builder<Pair<Future<List<String>>, Future<List<String>>>>, SinkShape<List<String>>, SinkShape<List<String>>>() {
              @Override
              public void apply(Builder<Pair<Future<List<String>>, Future<List<String>>>> b, SinkShape<List<String>> o1,
                  SinkShape<List<String>> o2) throws Exception {
                final UniformFanOutShape<String, String> fair = b.graph(new Fair<String>());
                b.edge(b.source(in), fair.in());
                b.flow(fair.out(0), Flow.of(String.class).grouped(100), o1.inlet());
                b.flow(fair.out(1), Flow.of(String.class).grouped(100), o2.inlet());
              }
            }).run(materializer);

    final List<String> result1 = Await.result(result.first(), Duration.apply(3, TimeUnit.SECONDS));
    final List<String> result2 = Await.result(result.second(), Duration.apply(3, TimeUnit.SECONDS));
    
    // we can't know exactly which elements that go to each output, because if subscription/request 
    // from one of the downstream is delayed the elements will be pushed to the other output
    final HashSet<String> all = new HashSet<String>();
    all.addAll(result1);
    all.addAll(result2);
    assertEquals(new HashSet<String>(Arrays.asList("a", "b", "c", "d", "e")), all);
  }

  @Test
  public void mustBuildSimpleRoundRobinRoute() throws Exception {
    final Pair<Future<List<String>>, Future<List<String>>> result = FlowGraph
        .factory()
        .closed(
            out1,
            out2,
            Keep.<Future<List<String>>, Future<List<String>>> both(),
            new Procedure3<Builder<Pair<Future<List<String>>, Future<List<String>>>>, SinkShape<List<String>>, SinkShape<List<String>>>() {
              @Override
              public void apply(Builder<Pair<Future<List<String>>, Future<List<String>>>> b, SinkShape<List<String>> o1,
                  SinkShape<List<String>> o2) throws Exception {
                final UniformFanOutShape<String, String> robin = b.graph(new StrictRoundRobin<String>());
                b.edge(b.source(in), robin.in());
                b.flow(robin.out(0), Flow.of(String.class).grouped(100), o1.inlet());
                b.flow(robin.out(1), Flow.of(String.class).grouped(100), o2.inlet());
              }
            }).run(materializer);

    final List<String> result1 = Await.result(result.first(), Duration.apply(3, TimeUnit.SECONDS));
    final List<String> result2 = Await.result(result.second(), Duration.apply(3, TimeUnit.SECONDS));
    
    assertEquals(Arrays.asList("a", "c", "e"), result1);
    assertEquals(Arrays.asList("b", "d"), result2);
  }

  @Test
  public void mustBuildSimpleUnzip() throws Exception {
    final List<Pair<Integer, String>> pairs = new ArrayList<Pair<Integer,String>>();
    pairs.add(new Pair<Integer, String>(1, "A"));
    pairs.add(new Pair<Integer, String>(2, "B"));
    pairs.add(new Pair<Integer, String>(3, "C"));
    pairs.add(new Pair<Integer, String>(4, "D"));
    
    final Pair<Future<List<Integer>>, Future<List<String>>> result = FlowGraph
        .factory()
        .closed(
            Sink.<List<Integer>> head(),
            out2,
            Keep.<Future<List<Integer>>, Future<List<String>>> both(),
            new Procedure3<Builder<Pair<Future<List<Integer>>, Future<List<String>>> >, SinkShape<List<Integer>>, SinkShape<List<String>>>() {
              @Override
              public void apply(Builder<Pair<Future<List<Integer>>, Future<List<String>>> > b, SinkShape<List<Integer>> o1,
                  SinkShape<List<String>> o2) throws Exception {
                final FanOutShape2<Pair<Integer, String>, Integer, String> unzip = b.graph(new Unzip<Integer, String>());
                final Outlet<Pair<Integer, String>> src = b.source(Source.from(pairs));
                b.edge(src, unzip.in());
                b.flow(unzip.out0(), Flow.of(Integer.class).grouped(100), o1.inlet());
                b.flow(unzip.out1(), Flow.of(String.class).grouped(100), o2.inlet());
              }
            }).run(materializer);

    final List<Integer> result1 = Await.result(result.first(), Duration.apply(3, TimeUnit.SECONDS));
    final List<String> result2 = Await.result(result.second(), Duration.apply(3, TimeUnit.SECONDS));
    
    assertEquals(Arrays.asList(1, 2, 3, 4), result1);
    assertEquals(Arrays.asList("A", "B", "C", "D"), result2);
  }

  /**
   * This is fair in that sense that after enqueueing to an output it yields to
   * other output if they are have requested elements. Or in other words, if all
   * outputs have demand available at the same time then in finite steps all
   * elements are enqueued to them.
   */
  static public class Fair<T> extends FlexiRoute<T, UniformFanOutShape<T, T>> {
    public Fair() {
      super(new UniformFanOutShape<T, T>(2), Attributes.name("Fair"));
    }
    @Override
    public RouteLogic<T> createRouteLogic(final UniformFanOutShape<T, T> s) {
      return new RouteLogic<T>() {
        
        private State<OutPort, T> emitToAnyWithDemand = new State<OutPort, T>(demandFromAny(s.out(0), s.out(1))) {
          @SuppressWarnings("unchecked")
          @Override
          public State<T, T> onInput(RouteLogicContext<T> ctx, OutPort out, T element) {
            ctx.emit((Outlet<T>) out, element);
            return sameState();
          }
        };

        @Override
        public State<BoxedUnit, T> initialState() {
          return new State<BoxedUnit, T>(demandFromAll(s.out(0), s.out(1))) {
            @Override
            public State<OutPort, T> onInput(RouteLogicContext<T> ctx, BoxedUnit x, T element) {
              ctx.emit(s.out(0), element);
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
  static public class StrictRoundRobin<T> extends FlexiRoute<T, UniformFanOutShape<T, T>> {
    public StrictRoundRobin() {
      super(new UniformFanOutShape<T, T>(2), Attributes.name("StrictRoundRobin"));
    }
    @Override
    public RouteLogic<T> createRouteLogic(final UniformFanOutShape<T, T> s) {
      return new RouteLogic<T>() {
        private State<Outlet<T>, T> toOutput1 = new State<Outlet<T>, T>(demandFrom(s.out(0))) {
          @Override
          public State<Outlet<T>, T> onInput(RouteLogicContext<T> ctx, Outlet<T> preferred, T element) {
            ctx.emit(preferred, element);
            return toOutput2;
          }
        };

        private State<Outlet<T>, T> toOutput2 = new State<Outlet<T>, T>(demandFrom(s.out(1))) {
          @Override
          public State<Outlet<T>, T> onInput(RouteLogicContext<T> ctx, Outlet<T> preferred, T element) {
            ctx.emit(preferred, element);
            return toOutput1;
          }
        };

        @Override
        public State<Outlet<T>, T> initialState() {
          return toOutput1;
        }

      };
    }
  }

  static public class Unzip<A, B> extends FlexiRoute<Pair<A, B>, FanOutShape2<Pair<A, B>, A, B>> {
    public Unzip() {
      super(new FanOutShape2<Pair<A, B>, A, B>("Unzip"), Attributes.name("Unzip"));
    }
    @Override
    public RouteLogic<Pair<A, B>> createRouteLogic(final FanOutShape2<Pair<A, B>, A, B> s) {
      return new RouteLogic<Pair<A, B>>() {
        @Override
        public State<BoxedUnit, Pair<A, B>> initialState() {
          return new State<BoxedUnit, Pair<A, B>>(demandFromAll(s.out0(), s.out1())) {
            @Override
            public State<BoxedUnit, Pair<A, B>> onInput(RouteLogicContext<Pair<A, B>> ctx, BoxedUnit x,
                Pair<A, B> element) {
              ctx.emit(s.out0(), element.first());
              ctx.emit(s.out1(), element.second());
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
