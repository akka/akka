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
import akka.stream.FlowMaterializer;
import akka.stream.testkit.AkkaSpec;
import akka.stream.javadsl.FlexiMerge;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.japi.Pair;

public class FlexiMergeTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlexiMergeTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final FlowMaterializer materializer = FlowMaterializer.create(system);

  final Source<String> in1 = Source.from(Arrays.asList("a", "b", "c", "d"));
  final Source<String> in2 = Source.from(Arrays.asList("e", "f"));

  final KeyedSink<String, Publisher<String>> out1 = Sink.publisher();

  @Test
  public void mustBuildSimpleFairMerge() throws Exception {
    Fair<String> merge = new Fair<String>();

    MaterializedMap m = FlowGraph.builder().addEdge(in1, merge.input1()).addEdge(in2, merge.input2())
        .addEdge(merge.out(), out1).build().run(materializer);

    final Publisher<String> pub = m.get(out1);
    final Future<List<String>> all = Source.from(pub).grouped(100).runWith(Sink.<List<String>>head(), materializer);
    final List<String> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(
        new HashSet<String>(Arrays.asList("a", "b", "c", "d", "e", "f")), 
        new HashSet<String>(result));
  }
  
  @Test
  public void mustBuildSimpleRoundRobinMerge() throws Exception {
    StrictRoundRobin<String> merge = new StrictRoundRobin<String>();

    MaterializedMap m = FlowGraph.builder().addEdge(in1, merge.input1()).addEdge(in2, merge.input2())
        .addEdge(merge.out(), out1).build().run(materializer);

    final Publisher<String> pub = m.get(out1);
    final Future<List<String>> all = Source.from(pub).grouped(100).runWith(Sink.<List<String>>head(), materializer);
    final List<String> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(Arrays.asList("a", "e", "b", "f", "c", "d"), result);
  }
  
  @Test
  @SuppressWarnings("unchecked")
  public void mustBuildSimpleZip() throws Exception {
    Zip<Integer, String> zip = new Zip<Integer, String>();
    
    Source<Integer> inA = Source.from(Arrays.asList(1, 2, 3, 4));
    Source<String> inB = Source.from(Arrays.asList("a", "b", "c"));
    KeyedSink<Pair<Integer, String>, Publisher<Pair<Integer, String>>> out = Sink.publisher();

    MaterializedMap m = FlowGraph.builder().addEdge(inA, zip.inputA).addEdge(inB, zip.inputB)
        .addEdge(zip.out(), out).build().run(materializer);

    final Publisher<Pair<Integer, String>> pub = m.get(out);
    final Future<List<Pair<Integer, String>>> all = Source.from(pub).grouped(100).
        runWith(Sink.<List<Pair<Integer, String>>>head(), materializer);
    final List<Pair<Integer, String>> result = Await.result(all, Duration.apply(3, TimeUnit.SECONDS));
    assertEquals(
        Arrays.asList(new Pair(1, "a"), new Pair(2, "b"), new Pair(3, "c")),
        result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void mustBuildTripleZipUsingReadAll() throws Exception {
    TripleZip<Long, Integer, String> zip = new TripleZip<Long, Integer, String>();

    Source<Long> inA = Source.from(Arrays.asList(1L, 2L, 3L, 4L));
    Source<Integer> inB = Source.from(Arrays.asList(1, 2, 3, 4));
    Source<String> inC = Source.from(Arrays.asList("a", "b", "c"));
    KeyedSink<Triple<Long, Integer, String>, Publisher<Triple<Long, Integer, String>>> out = Sink.publisher();

    MaterializedMap m = FlowGraph.builder()
      .addEdge(inA, zip.inputA)
      .addEdge(inB, zip.inputB)
      .addEdge(inC, zip.inputC)
      .addEdge(zip.out(), out)
      .build().run(materializer);

    final Publisher<Triple<Long, Integer, String>> pub = m.get(out);
    final Future<List<Triple<Long, Integer, String>>> all = Source.from(pub).grouped(100).
        runWith(Sink.<List<Triple<Long, Integer, String>>>head(), materializer);
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
  static public class Fair<T> extends FlexiMerge<T, T> {

    private final InputPort<T, T> input1 = createInputPort();
    private final InputPort<T, T> input2 = createInputPort();

    public Fair() {
      super("fairMerge");
    }

    public InputPort<T, T> input1() {
      return input1;
    }

    public InputPort<T, T> input2() {
      return input2;
    }

    @Override
    public MergeLogic<T, T> createMergeLogic() {
      return new MergeLogic<T, T>() {
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          return Arrays.asList(input1.handle(), input2.handle());
        }

        @Override
        public State<T, T> initialState() {
          return new State<T, T>(readAny(input1, input2)) {
            @Override
            public State<T, T> onInput(MergeLogicContext<T> ctx, InputHandle inputHandle, T element) {
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
  static public class StrictRoundRobin<T> extends FlexiMerge<T, T> {

    private final InputPort<T, T> input1 = createInputPort();
    private final InputPort<T, T> input2 = createInputPort();

    public StrictRoundRobin() {
      super("roundRobinMerge");
    }

    public InputPort<T, T> input1() {
      return input1;
    }

    public InputPort<T, T> input2() {
      return input2;
    }

    @Override
    public MergeLogic<T, T> createMergeLogic() {
      return new MergeLogic<T, T>() {
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          return Arrays.asList(input1.handle(), input2.handle());
        }

        private final CompletionHandling<T> emitOtherOnClose = new CompletionHandling<T>() {
          @Override
          public State<T, T> onComplete(MergeLogicContext<T> ctx, InputHandle input) {
            ctx.changeCompletionHandling(defaultCompletionHandling());
            return readRemaining(other(input));
          }

          @Override
          public State<T, T> onError(MergeLogicContext<T> ctx, InputHandle inputHandle, Throwable cause) {
            ctx.error(cause);
            return sameState();
          }
        };

        private InputHandle other(InputHandle input) {
          if (input == input1)
            return input2;
          else
            return input1;
        }

        private final State<T, T> read1 = new State<T, T>(read(input1)) {
          @Override
          public State<T, T> onInput(MergeLogicContext<T> ctx, InputHandle inputHandle, T element) {
            ctx.emit(element);
            return read2;
          }
        };

        private final State<T, T> read2 = new State<T, T>(read(input2)) {
          @Override
          public State<T, T> onInput(MergeLogicContext<T> ctx, InputHandle inputHandle, T element) {
            ctx.emit(element);
            return read1;
          }
        };

        private State<T, T> readRemaining(InputHandle input) {
          return new State<T, T>(read(input)) {
            @Override
            public State<T, T> onInput(MergeLogicContext<T> ctx, InputHandle inputHandle, T element) {
              ctx.emit(element);
              return sameState();
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
  
  static public class Zip<A, B> extends FlexiMerge<A, Pair<A, B>> {

    public final InputPort<A, Pair<A, B>> inputA = createInputPort();
    public final InputPort<B, Pair<A, B>> inputB = createInputPort();

    public Zip() {
      super("zip");
    }

    @Override
    public MergeLogic<A, Pair<A, B>> createMergeLogic() {
      return new MergeLogic<A, Pair<A, B>>() {
        
        private A lastInA = null;
        
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          if(inputCount != 2) 
            throw new IllegalArgumentException("Zip must have two connected inputs, was " + inputCount);
          return Arrays.asList(inputA.handle(), inputB.handle());
        }

        private final State<A, Pair<A, B>> readA = new State<A, Pair<A, B>>(read(inputA)) {
          @Override
          public State<B, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, InputHandle inputHandle, A element) {
            lastInA = element;
            return readB;
          }
        };
        
        private final State<B, Pair<A, B>> readB = new State<B, Pair<A, B>>(read(inputB)) {
          @Override
          public State<A, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, InputHandle inputHandle, B element) {
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
  }

  static public class TripleZip<A, B, C> extends FlexiMerge<FlexiMerge.ReadAllInputs, Triple<A, B, C>> {

    public final InputPort<A, Triple<A, B, C>> inputA = createInputPort();
    public final InputPort<B, Triple<A, B, C>> inputB = createInputPort();
    public final InputPort<C, Triple<A, B, C>> inputC = createInputPort();

    public TripleZip() {
      super("triple-zip");
    }

    @Override
    public MergeLogic<ReadAllInputs, Triple<A, B, C>> createMergeLogic() {
      return new MergeLogic<ReadAllInputs, Triple<A, B, C>>() {

        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          if (inputCount != 3)
            throw new IllegalArgumentException("Zip must have two connected inputs, was " + inputCount);
          return Arrays.asList(inputA.handle(), inputB.handle());
        }

        @Override
        public State<ReadAllInputs, Triple<A, B, C>> initialState() {
          return new State<ReadAllInputs, Triple<A, B, C>>(readAll(inputA, inputB, inputC)) {
            @Override
            public State<ReadAllInputs, Triple<A, B, C>> onInput(MergeLogicContext<Triple<A, B, C>> ctx, InputHandle input, ReadAllInputs inputs) {
              final A a = inputs.getOrDefault(inputA, null);
              final B b = inputs.getOrDefault(inputB, null);
              final C c = inputs.getOrDefault(inputC, null);

              ctx.emit(new Triple<A, B, C>(a, b, c));

              return sameState();
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
