/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import akka.stream.*;
import akka.stream.javadsl.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import akka.actor.ActorSystem;
import akka.japi.Pair;
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

  final Materializer mat = ActorMaterializer.create(system);

  final static SilenceSystemOut.System System = SilenceSystemOut.get();

  static//#fleximerge-zip-readall
  public class Zip<A, B>
    extends FlexiMerge<FlexiMerge.ReadAllInputs, Pair<A, B>, FanInShape2<A, B, Pair<A, B>>> {

    public Zip() {
      super(new FanInShape2<A, B, Pair<A, B>>("Zip"), Attributes.name("Zip"));
    }

    @Override
    public MergeLogic<ReadAllInputs, Pair<A, B>>createMergeLogic(
        final FanInShape2<A, B, Pair<A, B>> s) {
      return new MergeLogic<ReadAllInputs, Pair<A, B>>() {
        @Override
        public State<ReadAllInputs, Pair<A, B>> initialState() {
          return new State<ReadAllInputs, Pair<A, B>>(readAll(s.in0(), s.in1())) {
            @Override
            public State<ReadAllInputs, Pair<A, B>> onInput(
                    MergeLogicContext<Pair<A, B>> ctx,
                    InPort input,
                    ReadAllInputs inputs) {
              final A a = inputs.get(s.in0());
              final B b = inputs.get(s.in1());

              ctx.emit(new Pair<A, B>(a, b));

              return this;
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
  public class Zip2<A, B> extends FlexiMerge<A, Pair<A, B>, FanInShape2<A, B, Pair<A, B>>> {
    public Zip2() {
      super(new FanInShape2<A, B, Pair<A, B>>("Zip2"), Attributes.name("Zip2"));
    }

    @Override
    public MergeLogic<A, Pair<A, B>> createMergeLogic(final FanInShape2<A, B, Pair<A, B>> s) {
      return new MergeLogic<A, Pair<A, B>>() {

        private A lastInA = null;

        private final State<A, Pair<A, B>> readA = new State<A, Pair<A, B>>(read(s.in0())) {
          @Override
          public State<B, Pair<A, B>> onInput(
              MergeLogicContext<Pair<A, B>> ctx, InPort inputHandle, A element) {
            lastInA = element;
            return readB;
          }
        };

        private final State<B, Pair<A, B>> readB = new State<B, Pair<A, B>>(read(s.in1())) {
          @Override
          public State<A, Pair<A, B>> onInput(
              MergeLogicContext<Pair<A, B>> ctx, InPort inputHandle, B element) {
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
  public class ImportantWithBackups<T> extends FlexiMerge<T, T, FanInShape3<T, T, T, T>> {
    public ImportantWithBackups() {
      super(
              new FanInShape3<T, T, T, T>("ImportantWithBackup"),
              Attributes.name("ImportantWithBackup")
      );
    }


    @Override
    public MergeLogic<T, T> createMergeLogic(final FanInShape3<T, T, T, T> s) {
      return new MergeLogic<T, T>() {

        @Override
        public CompletionHandling<T> initialCompletionHandling() {
          return new CompletionHandling<T>() {
            @Override
            public State<T, T> onUpstreamFinish(MergeLogicContextBase<T> ctx,
                                                InPort input) {
              if (input == s.in0()) {
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
                                                 InPort input, Throwable cause) {
              if (input == s.in0()) {
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
          return new State<T, T>(readAny(s.in0(), s.in1(), s.in2())) {
            @Override
            public State<T, T> onInput(MergeLogicContext<T> ctx,
                                       InPort input, T element) {
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
  public class PreferringMerge
    extends FlexiMerge<Integer, Integer, FanInShape3<Integer, Integer, Integer, Integer>> {
    public PreferringMerge() {
      super(
              new FanInShape3<Integer, Integer, Integer, Integer>("PreferringMerge"),
              Attributes.name("PreferringMerge")
      );
    }

    @Override
    public MergeLogic<Integer, Integer> createMergeLogic(
        FanInShape3<Integer, Integer, Integer, Integer> s) {
      return new MergeLogic<Integer, Integer>() {

        @Override
        public State<Integer, Integer> initialState() {
          return new State<Integer, Integer>(readPreferred(s.in0(), s.in1(), s.in2())) {
            @Override
            public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx,
                                                   InPort inputHandle, Integer element) {
              ctx.emit(element);
              return sameState();
            }
          };
        }
      };
    }
  }
  //#flexi-preferring-merge

  static public class FlexiReadConditions extends FlexiMerge<Integer, Integer, FanInShape3<Integer, Integer, Integer, Integer>> {
    public FlexiReadConditions() {
      super(
              new FanInShape3<Integer, Integer, Integer, Integer>("ReadConditions"),
              Attributes.name("ReadConditions")
      );
    }

    @Override
    @SuppressWarnings("unused")
    public MergeLogic<Integer, Integer> createMergeLogic(FanInShape3<Integer, Integer, Integer, Integer> s) {
      return new MergeLogic<Integer, Integer>() {
        //#read-conditions
        private final State<Integer, Integer> onlyFirst =
                new State<Integer, Integer>(read(s.in0())) {
                  @Override
                  public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx,
                                                         InPort inputHandle, Integer element) {
                    ctx.emit(element);
                    return sameState();
                  }
                };

        private final State<Integer, Integer> firstOrThird =
                new State<Integer, Integer>(readAny(s.in0(), s.in2())) {
                  @Override
                  public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx,
                                                         InPort inputHandle, Integer element) {
                    ctx.emit(element);
                    return sameState();
                  }
                };

        private final State<ReadAllInputs, Integer> firstAndSecond =
                new State<ReadAllInputs, Integer>(readAll(s.in0(), s.in1())) {
                  @Override
                  public State<ReadAllInputs, Integer> onInput(MergeLogicContext<Integer> ctx,
                                                               InPort inputHandle, ReadAllInputs inputs) {
                    Integer a = inputs.getOrDefault(s.in0(), 0);
                    Integer b = inputs.getOrDefault(s.in1(), 0);
                    ctx.emit(a + b);
                    return sameState();
                  }
                };

        private final State<Integer, Integer> mostlyFirst =
                new State<Integer, Integer>(readPreferred(s.in0(), s.in1(), s.in2())) {
                  @Override
                  public State<Integer, Integer> onInput(MergeLogicContext<Integer> ctx,
                                                         InPort inputHandle, Integer element) {
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
    final Sink<Pair<Integer, String>, Future<Pair<Integer, String>>> head =
            Sink.<Pair<Integer, String>>head();

    final Future<Pair<Integer, String>> future = FlowGraph.factory().closed(head,
            (builder, headSink) -> {
              final FanInShape2<Integer, String, Pair<Integer, String>> zip =
                builder.graph(new Zip<Integer, String>());
              builder.from(Source.single(1)).to(zip.in0());
              builder.from(Source.single("A")).to(zip.in1());
              builder.from(zip.out()).to(headSink);

            }).run(mat);
    //#fleximerge-zip-connecting

    assertEquals(new Pair<>(1, "A"),
            Await.result(future, FiniteDuration.create(3, TimeUnit.SECONDS)));
  }

  @Test
  public void demonstrateZipWithStates() throws Exception {
    final Sink<Pair<Integer, String>, Future<Pair<Integer, String>>> head =
            Sink.<Pair<Integer, String>>head();

    final Future<Pair<Integer, String>> future = FlowGraph.factory().closed(head,
            (builder, headSink) -> {
              final FanInShape2<Integer, String, Pair<Integer, String>> zip = builder.graph(new Zip2<Integer, String>());
              builder.from(Source.repeat(1)).to(zip.in0());
              builder.from(Source.single("A")).to(zip.in1());
              builder.from(zip.out()).to(headSink);

            }).run(mat);

    assertEquals(new Pair<>(1, "A"),
            Await.result(future, FiniteDuration.create(3, TimeUnit.SECONDS)));
  }

  @Test
  public void demonstrateImportantWithBackup() {
    FlowGraph.factory().closed((builder) -> {
      final FanInShape3<Integer, Integer, Integer, Integer> importantWithBackups = builder.graph(new ImportantWithBackups<Integer>());

      builder.from(Source.single(1)).to(importantWithBackups.in0());
      builder.from(Source.single(2)).to(importantWithBackups.in1());
      builder.from(Source.<Integer>failed(new RuntimeException("Boom!"))).to(importantWithBackups.in2());
      builder.from(importantWithBackups.out()).to(Sink.ignore());
    }).run(mat);
  }

}