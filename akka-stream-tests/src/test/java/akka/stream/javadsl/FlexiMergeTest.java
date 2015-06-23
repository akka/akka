/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import java.util.Arrays;
import java.util.List;
import java.util.HashSet;

import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;

import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.javadsl.FlowGraph.Builder;
import akka.stream.testkit.AkkaSpec;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import akka.japi.Pair;
import akka.japi.function.Procedure2;

public class FlexiMergeTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlexiMergeTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final ActorMaterializer materializer = ActorMaterializer.create(system);

  final Source<String, BoxedUnit> in1 = Source.from(Arrays.asList("a", "b", "c", "d"));
  final Source<String, BoxedUnit> in2 = Source.from(Arrays.asList("e", "f"));

  final Sink<String, Publisher<String>> out1 = Sink.publisher();

  @Test
  public void mustBuildSimpleFairMerge() throws Exception {
    final Future<List<String>> all = FlowGraph
        .factory()
        .closed(Sink.<List<String>> head(),
            new Procedure2<Builder<Future<List<String>> >, SinkShape<List<String>>>() {
              @Override
              public void apply(Builder<Future<List<String>> > b, SinkShape<List<String>> sink)
                  throws Exception {
                final UniformFanInShape<String, String> merge = b.graph(new Fair<String>());
                b.edge(b.source(in1), merge.in(0));
                b.edge(b.source(in2), merge.in(1));
                b.flow(merge.out(), Flow.of(String.class).grouped(10), sink.inlet());
              }
            }).run(materializer);

    final List<String> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(
        new HashSet<String>(Arrays.asList("a", "b", "c", "d", "e", "f")), 
        new HashSet<String>(result));
  }
  
  @Test
  public void mustBuildSimpleRoundRobinMerge() throws Exception {
    final Future<List<String>> all = FlowGraph
        .factory()
        .closed(Sink.<List<String>> head(),
            new Procedure2<Builder<Future<List<String>>>, SinkShape<List<String>>>() {
              @Override
              public void apply(Builder<Future<List<String>>> b, SinkShape<List<String>> sink)
                  throws Exception {
                final UniformFanInShape<String, String> merge = b.graph(new StrictRoundRobin<String>());
                b.edge(b.source(in1), merge.in(0));
                b.edge(b.source(in2), merge.in(1));
                b.flow(merge.out(), Flow.of(String.class).grouped(10), sink.inlet());
              }
            }).run(materializer);

    final List<String> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(Arrays.asList("a", "e", "b", "f", "c", "d"), result);
  }
  
  @Test
  @SuppressWarnings("unchecked")
  public void mustBuildSimpleZip() throws Exception {
    final Source<Integer, BoxedUnit> inA = Source.from(Arrays.asList(1, 2, 3, 4));
    final Source<String, BoxedUnit> inB = Source.from(Arrays.asList("a", "b", "c"));

    final Future<List<Pair<Integer, String>>> all = FlowGraph
        .factory()
        .closed(Sink.<List<Pair<Integer, String>>>head(),
                new Procedure2<Builder<Future<List<Pair<Integer, String>>>>, SinkShape<List<Pair<Integer, String>>>>() {
                    @Override
                    public void apply(Builder<Future<List<Pair<Integer, String>>>> b, SinkShape<List<Pair<Integer, String>>> sink)
                            throws Exception {
                        final FanInShape2<Integer, String, Pair<Integer, String>> zip = b.graph(new Zip<Integer, String>());
                        b.edge(b.source(inA), zip.in0());
                        b.edge(b.source(inB), zip.in1());
                        b.flow(zip.out(), Flow.<Pair<Integer, String>>create().grouped(10), sink.inlet());
                    }
                }).run(materializer);
    
    final List<Pair<Integer, String>> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(
        Arrays.asList(new Pair(1, "a"), new Pair(2, "b"), new Pair(3, "c")),
        result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void mustBuildTripleZipUsingReadAll() throws Exception {
    final Source<Long, BoxedUnit> inA = Source.from(Arrays.asList(1L, 2L, 3L, 4L));
    final Source<Integer, BoxedUnit> inB = Source.from(Arrays.asList(1, 2, 3, 4));
    final Source<String, BoxedUnit> inC = Source.from(Arrays.asList("a", "b", "c"));

    final Future<List<Triple<Long, Integer, String>>> all = FlowGraph
        .factory()
        .closed(Sink.<List<Triple<Long, Integer, String>>> head(),
            new Procedure2<Builder<Future<List<Triple<Long, Integer, String>>>>, SinkShape<List<Triple<Long, Integer, String>>>>() {
              @Override
              public void apply(Builder<Future<List<Triple<Long, Integer, String>>>> b, SinkShape<List<Triple<Long, Integer, String>>> sink)
                  throws Exception {
                final FanInShape3<Long, Integer, String, Triple<Long, Integer, String>> zip =
                    b.graph(new TripleZip<Long, Integer, String>());
                b.edge(b.source(inA), zip.in0());
                b.edge(b.source(inB), zip.in1());
                b.edge(b.source(inC), zip.in2());
                b.flow(zip.out(), Flow.<Triple<Long, Integer, String>> create().grouped(10), sink.inlet());
              }
            }).run(materializer);

    final List<Triple<Long, Integer, String>> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(
        Arrays.asList(new Triple(1L, 1, "a"), new Triple(2L, 2, "b"), new Triple(3L, 3, "c")),
        result);
  }

  /**
   * This is fair in that sense that after dequeueing from an input it yields to
   * other inputs if they are available. Or in other words, if all inputs have
   * elements available at the same time then in finite steps all those elements
   * are dequeued from them.
   */
  static public class Fair<T> extends FlexiMerge<T, T, UniformFanInShape<T, T>> {
    public Fair() {
      super(new UniformFanInShape<T, T>(2), Attributes.name("Fair"));
    }
    @Override
    public MergeLogic<T, T> createMergeLogic(final UniformFanInShape<T, T> s) {
      return new MergeLogic<T, T>() {
        @Override
        public State<T, T> initialState() {
          return new State<T, T>(this.<T>readAny(s.in(0), s.in(1))) {
            @Override
            public State<T, T> onInput(MergeLogicContext<T> ctx, InPort in, T element) {
              ctx.emit(element);
              return sameState();
            }
          };
        }
      };
    }
  }

  /**
   * It never skips an input while cycling but waits on it instead (closed
   * inputs are skipped though). The fair merge above is a non-strict
   * round-robin (skips currently unavailable inputs).
   */
  static public class StrictRoundRobin<T> extends FlexiMerge<T, T, UniformFanInShape<T, T>> {
    public StrictRoundRobin() {
      super(new UniformFanInShape<T, T>(2), Attributes.name("StrictRoundRobin"));
    }
    @Override
    public MergeLogic<T, T> createMergeLogic(final UniformFanInShape<T, T> s) {
      return new MergeLogic<T, T>() {
        private final CompletionHandling<T> emitOtherOnClose = new CompletionHandling<T>() {
          @Override
          public State<T, T> onUpstreamFinish(MergeLogicContextBase<T> ctx, InPort input) {
            ctx.changeCompletionHandling(defaultCompletionHandling());
            return readRemaining(other(input));
          }
          @Override
          public State<T, T> onUpstreamFailure(MergeLogicContextBase<T> ctx, InPort inputHandle, Throwable cause) {
              ctx.fail(cause);
            return sameState();
          }
        };

        private Inlet<T> other(InPort input) {
          if (input == s.in(0))
            return s.in(1);
          else
            return s.in(0);
        }

        private final State<T, T> read1 = new State<T, T>(read(s.in(0))) {
          @Override
          public State<T, T> onInput(MergeLogicContext<T> ctx, InPort inputHandle, T element) {
            ctx.emit(element);
            return read2;
          }
        };

        private final State<T, T> read2 = new State<T, T>(read(s.in(1))) {
          @Override
          public State<T, T> onInput(MergeLogicContext<T> ctx, InPort inputHandle, T element) {
            ctx.emit(element);
            return read1;
          }
        };

        private State<T, T> readRemaining(Inlet<T> input) {
          return new State<T, T>(read(input)) {
            @Override
            public State<T, T> onInput(MergeLogicContext<T> ctx, InPort inputHandle, T element) {
              ctx.emit(element);
              return this;
            }
          };
        }

        @Override
        public State<T, T> initialState() {
          return read1;
        }

        @Override
        public CompletionHandling<T> initialCompletionHandling() {
          return emitOtherOnClose;
        }

      };
    }
  }
  
  static public class Zip<A, B> extends FlexiMerge<A, Pair<A, B>, FanInShape2<A, B, Pair<A, B>>> {
    public Zip() {
      super(new FanInShape2<A, B, Pair<A, B>>("Zip"), Attributes.name("Zip"));
    }
    @Override
    public MergeLogic<A, Pair<A, B>> createMergeLogic(final FanInShape2<A, B, Pair<A, B>> s) {
      return new MergeLogic<A, Pair<A, B>>() {
        
        private A lastInA = null;
        
        private final State<A, Pair<A, B>> readA = new State<A, Pair<A, B>>(read(s.in0())) {
          @Override
          public State<B, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, InPort inputHandle, A element) {
            lastInA = element;
            return readB;
          }
        };
        
        private final State<B, Pair<A, B>> readB = new State<B, Pair<A, B>>(read(s.in1())) {
          @Override
          public State<A, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, InPort inputHandle, B element) {
            ctx.emit(new Pair<A, B>(lastInA, element));
            return readA;
          }
        };

        @Override
        public State<A, Pair<A, B>> initialState() {
          return readA;
        }

        @Override
        public CompletionHandling<Pair<A, B>> initialCompletionHandling() {
          return eagerClose();
        }

      };
    }
  }

  static public class Triple<A, B, C> {
    public final A a;
    public final B b;
    public final C c;

    public Triple(A a, B b, C c) {
      this.a = a;
      this.b = b;
      this.c = c;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Triple triple = (Triple) o;

      if (a != null ? !a.equals(triple.a) : triple.a != null) {
        return false;
      }
      if (b != null ? !b.equals(triple.b) : triple.b != null) {
        return false;
      }
      if (c != null ? !c.equals(triple.c) : triple.c != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      int result = a != null ? a.hashCode() : 0;
      result = 31 * result + (b != null ? b.hashCode() : 0);
      result = 31 * result + (c != null ? c.hashCode() : 0);
      return result;
    }
    
    public String toString() {
      return "(" + a + ", " + b + ", " + c + ")"; 
    }
  }

  static public class TripleZip<A, B, C> extends FlexiMerge<FlexiMerge.ReadAllInputs, Triple<A, B, C>, FanInShape3<A, B, C, Triple<A, B, C>>> {
    public TripleZip() {
      super(new FanInShape3<A, B, C, Triple<A, B, C>>("TripleZip"), Attributes.name("TripleZip"));
    }
    @Override
    public MergeLogic<ReadAllInputs, Triple<A, B, C>> createMergeLogic(final FanInShape3<A, B, C, Triple<A, B, C>> s) {
      return new MergeLogic<ReadAllInputs, Triple<A, B, C>>() {
        @Override
        public State<ReadAllInputs, Triple<A, B, C>> initialState() {
          return new State<ReadAllInputs, Triple<A, B, C>>(readAll(s.in0(), s.in1(), s.in2())) {
            @Override
            public State<ReadAllInputs, Triple<A, B, C>> onInput(MergeLogicContext<Triple<A, B, C>> ctx, InPort input, ReadAllInputs inputs) {
              final A a = inputs.getOrDefault(s.in0(), null);
              final B b = inputs.getOrDefault(s.in1(), null);
              final C c = inputs.getOrDefault(s.in2(), null);

              ctx.emit(new Triple<A, B, C>(a, b, c));

              return this;
            }
          };
        }

        @Override
        public CompletionHandling<Triple<A, B, C>> initialCompletionHandling() {
          return eagerClose();
        }

      };
    }
  }

}
