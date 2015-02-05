/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.FlexiMerge;
import akka.stream.javadsl.FlexiMerge.ReadAllInputs;
import akka.stream.javadsl.FlowGraph;
import akka.stream.javadsl.KeyedSink;
import akka.stream.javadsl.MaterializedMap;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;

public class FlexiMergeDocTest {
  
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlexiMergeDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);
  
  static//#fleximerge-zip-readall
  public class Zip<A, B> extends FlexiMerge<ReadAllInputs, Pair<A, B>> {

    public final InputPort<A, Pair<A, B>> left = createInputPort();
    public final InputPort<B, Pair<A, B>> right = createInputPort();

    @Override
    public MergeLogic<ReadAllInputs, Pair<A, B>> createMergeLogic() {
      return new MergeLogic<ReadAllInputs, Pair<A, B>>() {
        
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          if(inputCount != 2) 
            throw new IllegalArgumentException(
                "Zip must have two connected inputs, was " + inputCount);
          return Arrays.asList(left, right);
        }

        @Override
        public State<ReadAllInputs, Pair<A, B>> initialState() {
          return new State<ReadAllInputs, Pair<A, B>>(readAll(left, right)) {
            @Override
            public State<ReadAllInputs, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, 
                InputHandle input, ReadAllInputs inputs) {
              final A a = inputs.getOrDefault(left, null);
              final B b = inputs.getOrDefault(right, null);
              ctx.emit(new Pair<A, B>(a, b));
              
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
  //#fleximerge-zip-readall
  
  
  static//#fleximerge-zip-states
  public class Zip2<A, B> extends FlexiMerge<A, Pair<A, B>> {

    public final InputPort<A, Pair<A, B>> left = createInputPort();
    public final InputPort<B, Pair<A, B>> right = createInputPort();

    @Override
    public MergeLogic<A, Pair<A, B>> createMergeLogic() {
      return new MergeLogic<A, Pair<A, B>>() {
        
        private A lastInA = null;
        
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          if(inputCount != 2) 
            throw new IllegalArgumentException(
                "Zip must have two connected inputs, was " + inputCount);
          return Arrays.asList(left, right);
        }

        private final State<A, Pair<A, B>> readA = new State<A, Pair<A, B>>(read(left)) {
          @Override
          public State<B, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, 
              InputHandle inputHandle, A element) {
            lastInA = element;
            return readB;
          }
        };
        
        private final State<B, Pair<A, B>> readB = new State<B, Pair<A, B>>(read(right)) {
          @Override
          public State<A, Pair<A, B>> onInput(MergeLogicContext<Pair<A, B>> ctx, 
              InputHandle inputHandle, B element) {
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
  //#fleximerge-zip-states
  
  static//#fleximerge-completion
  public class ImportantWithBackups<T> extends FlexiMerge<T, T> {

    public final InputPort<T, T> important = createInputPort();
    public final InputPort<T, T> replica1 = createInputPort();
    public final InputPort<T, T> replica2 = createInputPort();

    @Override
    public MergeLogic<T, T> createMergeLogic() {
      return new MergeLogic<T, T>() {
        
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          return Arrays.asList(important, replica1, replica2);
        }
        
        @Override
        public CompletionHandling<T> initialCompletionHandling() {
          return new CompletionHandling<T>() {
            @Override
            public State<T, T> onUpstreamFinish(MergeLogicContextBase<T> ctx, 
                InputHandle input) {
              if (input == important) {
                System.out.println("Important input completed, shutting down.");
                ctx.finish();
                return sameState();
              } else {
                System.out.printf("Replica %s completed, " +
                    "no more replicas available, " +
                    "applying eagerClose completion handling.\n", input);

                ctx.changeCompletionHandling(eagerClose());
                return sameState();
              }
            }

            @Override
            public State<T, T> onUpstreamFailure(MergeLogicContextBase<T> ctx, 
                InputHandle input, Throwable cause) {
              if (input == important) {
                ctx.fail(cause);
                return sameState();
              } else {
                System.out.printf("Replica %s failed, " +
                  "no more replicas available, " +
                  "applying eagerClose completion handling.\n", input);

                ctx.changeCompletionHandling(eagerClose());
                return sameState();
              }
            }
          };
          
        }

        @Override
        public State<T, T> initialState() {
          return new State<T, T>(readAny(important, replica1, replica2)) {
            @Override
            public State<T, T> onInput(MergeLogicContext<T> ctx, 
                InputHandle input, T element) {
              ctx.emit(element);
              return sameState();
            }
          };
        }

      };
    }
  }
  //#fleximerge-completion
  
  static //#flexi-preferring-merge
  public class PreferringMerge extends FlexiMerge<Integer, Integer> {

    public final InputPort<Integer, Integer> preferred = createInputPort();
    public final InputPort<Integer, Integer> secodary1 = createInputPort();
    public final InputPort<Integer, Integer> secondary2 = createInputPort();

    @Override
    public MergeLogic<Integer, Integer> createMergeLogic() {
      return new MergeLogic<Integer, Integer>() {
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          if(inputCount != 2) 
            throw new IllegalArgumentException(
                "PreferringMerge must have 3 connected inputs, was " + inputCount);
          return Arrays.asList(preferred, secodary1, secondary2);
        }

        @Override
        public State<Integer, Integer> initialState() {
          return new State<Integer, Integer>(readPreferred(preferred, secodary1, secondary2)) {
            @Override
            public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx, 
                InputHandle inputHandle, Integer element) {
              ctx.emit(element);
              return sameState();
            }
          };
        }
      };
    }
  }
  //#flexi-preferring-merge
  
  static public class FlexiReadConditions extends FlexiMerge<Integer, Integer> {

    //#read-conditions
    public final InputPort<Integer, Integer> first = createInputPort();
    public final InputPort<Integer, Integer> second = createInputPort();
    public final InputPort<Integer, Integer> third = createInputPort();
    //#read-conditions

    @Override
    @SuppressWarnings("unused")
    public MergeLogic<Integer, Integer> createMergeLogic() {
      return new MergeLogic<Integer, Integer>() {
        @Override
        public List<InputHandle> inputHandles(int inputCount) {
          if(inputCount != 2) 
            throw new IllegalArgumentException(
                "PreferringMerge must have 3 connected inputs, was " + inputCount);
          return Arrays.asList(first, second, third);
        }

        //#read-conditions
        private final State<Integer, Integer> onlyFirst =
          new State<Integer, Integer>(read(first)) {
            @Override
            public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx, 
                InputHandle inputHandle, Integer element) {
              ctx.emit(element);
              return sameState();
            }
          };
          
        private final State<Integer, Integer> firstOrThird =
            new State<Integer, Integer>(readAny(first, third)) {
              @Override
              public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx, 
                  InputHandle inputHandle, Integer element) {
                ctx.emit(element);
                return sameState();
              }
            };
            
        private final State<ReadAllInputs, Integer> firstAndSecond =
            new State<ReadAllInputs, Integer>(readAll(first, second)) {
              @Override
              public State<ReadAllInputs, Integer> onInput(MergeLogicContext<Integer> ctx, 
                  InputHandle inputHandle, ReadAllInputs inputs) {
                Integer a = inputs.getOrDefault(first, 0);
                Integer b = inputs.getOrDefault(second, 0);
                ctx.emit(a + b);
                return sameState();
              }
            };
          
        private final State<Integer, Integer> mostylFirst =
            new State<Integer, Integer>(readPreferred(first, second, third)) {
              @Override
              public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx, 
                  InputHandle inputHandle, Integer element) {
                ctx.emit(element);
                return sameState();
              }
            };
        //#read-conditions    
      
        @Override
        public State<Integer, Integer> initialState() {
          return onlyFirst;
        }
      };
    }
  }
  
  @Test
  public void demonstrateZipUsingReadAll() throws Exception {
    //#fleximerge-zip-connecting
    final KeyedSink<Pair<Integer, String>, Future<Pair<Integer, String>>> head = 
        Sink.<Pair<Integer, String>>head();
    final Zip<Integer, String> zip = new Zip<>();
    
    final MaterializedMap map = FlowGraph.builder()
      .addEdge(Source.single(1), zip.left)
      .addEdge(Source.single("A"), zip.right)
      .addEdge(zip.out(), head)
      .run(mat);
    //#fleximerge-zip-connecting    
    
    assertEquals(new Pair<>(1, "A"), 
        Await.result(map.get(head), FiniteDuration.create(3, TimeUnit.SECONDS)));
  }
  
  @Test
  public void demonstrateZipWithStates() throws Exception {
    final KeyedSink<Pair<Integer, String>, Future<Pair<Integer, String>>> head = 
        Sink.<Pair<Integer, String>>head();
    final Zip2<Integer, String> zip = new Zip2<>();
    
    final MaterializedMap map = FlowGraph.builder()
      .addEdge(Source.from(Arrays.asList(1, 2)), zip.left)
      .addEdge(Source.from(Arrays.asList("1", "2")), zip.right)
      .addEdge(zip.out(), head)
      .run(mat);
    
    assertEquals(new Pair<>(1, "1"), 
        Await.result(map.get(head), FiniteDuration.create(3, TimeUnit.SECONDS)));
  }

  @Test
  public void demonstrateImportantWithBackup() {
    final ImportantWithBackups<Integer> importantWithBackups =
        new ImportantWithBackups<>();
    FlowGraph.builder()
      .addEdge(Source.single(1), importantWithBackups.important)
      .addEdge(Source.single(2), importantWithBackups.replica1)
      .addEdge(Source.<Integer>failed(new RuntimeException("Boom!")), importantWithBackups.replica2)
      .addEdge(importantWithBackups.out(), Sink.ignore())
      .run(mat);
  }
  
}
