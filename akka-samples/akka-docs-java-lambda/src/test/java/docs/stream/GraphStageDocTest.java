package docs.stream;

import akka.actor.ActorSystem;
//#imports
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.dispatch.OnSuccess;
import akka.japi.Option;
import akka.japi.Predicate;
import akka.japi.function.Effect;
import akka.japi.function.Procedure;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.stage.*;
//#imports
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.testkit.JavaTestKit;
import akka.japi.Function;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import scala.Tuple2;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.*;

import static org.junit.Assert.assertEquals;

public class GraphStageDocTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowGraphDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);


  //#simple-source
  public class NumbersSource extends GraphStage<SourceShape<Integer>> {
    // Define the (sole) output port of this stage
    public final Outlet<Integer> out = Outlet.create("NumbersSource.out");

    // Define the shape of this stage, which is SourceShape with the port we defined above
    private final SourceShape<Integer> shape = SourceShape.of(out);
    @Override
    public SourceShape<Integer> shape() {
      return shape;
    }

    // This is where the actual (possibly stateful) logic is created
    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape()) {
        // All state MUST be inside the GraphStageLogic,
        // never inside the enclosing GraphStage.
        // This state is safe to access and modify from all the
        // callbacks that are provided by GraphStageLogic and the
        // registered handlers.
        private int counter = 1;

        {
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              push(out, counter);
              counter += 1;
            }
          });
        }

      };
    }

  }
  //#simple-source


  @Test
  public void demonstrateCustomSourceUsage() throws Exception {
    //#simple-source-usage
    // A GraphStage is a proper Graph, just like what GraphDSL.create would return
    Graph<SourceShape<Integer>, BoxedUnit> sourceGraph = new NumbersSource();

    // Create a Source from the Graph to access the DSL
    Source<Integer, BoxedUnit> mySource = Source.fromGraph(sourceGraph);

    // Returns 55
    Future<Integer> result1 = mySource.take(10).runFold(0, (sum, next) -> sum + next, mat);

    // The source is reusable. This returns 5050
    Future<Integer> result2 = mySource.take(100).runFold(0, (sum, next) -> sum + next, mat);
    //#simple-source-usage

    assertEquals(Await.result(result1, Duration.create(3, "seconds")), (Integer) 55);
    assertEquals(Await.result(result2, Duration.create(3, "seconds")), (Integer) 5050);
  }


  //#one-to-one
  public class Map<A, B> extends GraphStage<FlowShape<A, B>> {

    private final Function<A, B> f;

    public Map(Function<A, B> f) {
      this.f = f;
    }

    public final Inlet<A> in = Inlet.create("Map.in");
    public final Outlet<B> out = Outlet.create("Map.out");

    private final FlowShape<A, B> shape = FlowShape.of(in, out);
    @Override
    public FlowShape<A,B> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              try {
                push(out, f.apply(grab(in)));
              } catch (Exception ex) {
                failStage(ex);
              }
            }
          });
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(in);
            }
          });
        }
      };
    }

  }
  //#one-to-one

  @Test
  public void demonstrateOneToOne() throws Exception {
    // tests:
    final Graph<FlowShape<String, Integer>, BoxedUnit> stringLength =
      Flow.fromGraph(new Map<String, Integer>(new Function<String, Integer>() {
        @Override
        public Integer apply(String str) {
          return str.length();
        }
      }));

    Future<Integer> result =
      Source.from(Arrays.asList("one", "two", "three"))
        .via(stringLength)
        .runFold(0, (sum, n) -> sum + n, mat);

    assertEquals(new Integer(11), Await.result(result, Duration.create(3, "seconds")));
  }

  //#many-to-one
  public final class Filter<A> extends GraphStage<FlowShape<A, A>> {

    private final Predicate<A> p;

    public Filter(Predicate<A> p) {
      this.p = p;
    }

    public final Inlet<A> in = Inlet.create("Filter.in");
    public final Outlet<A> out = Outlet.create("Filter.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);

    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {
        {

          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              A elem = grab(in);
              if (p.test(elem)) {
                push(out, elem);
              } else {
                pull(in);
              }
            }
          });

          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(in);
            }
          });
        }
      };
    }
  }
  //#many-to-one

  @Test
  public void demonstrateAManyToOneElementGraphStage() throws Exception {

    // tests:
    Graph<FlowShape<Integer, Integer>, BoxedUnit> evenFilter =
      Flow.fromGraph(new Filter<Integer>(n -> n % 2 == 0));

    Future<Integer> result =
      Source.from(Arrays.asList(1, 2, 3, 4, 5, 6))
        .via(evenFilter)
        .runFold(0, (elem, sum) -> sum + elem, mat);

    assertEquals(new Integer(12), Await.result(result, Duration.create(3, "seconds")));
  }

  //#one-to-many
  public class Duplicator<A> extends GraphStage<FlowShape<A, A>> {

    public final Inlet<A> in = Inlet.create("Duplicator.in");
    public final Outlet<A> out = Outlet.create("Duplicator.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);

    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {
        // Again: note that all mutable state
        // MUST be inside the GraphStageLogic
        Option<A> lastElem = Option.none();

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              A elem = grab(in);
              lastElem = Option.some(elem);
              push(out, elem);
            }

            @Override
            public void onUpstreamFinish() {
              if (lastElem.isDefined()) {
                emit(out, lastElem.get());
              }
              complete(out);
            }
          });


          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              if (lastElem.isDefined()) {
                push(out, lastElem.get());
                lastElem = Option.none();
              } else {
                pull(in);
              }
            }
          });
        }
      };
    }
  }
  //#one-to-many

  @Test
  public void demonstrateAOneToManyElementGraphStage() throws Exception {
    // tests:
    Graph<FlowShape<Integer, Integer>, BoxedUnit> duplicator =
            Flow.fromGraph(new Duplicator<Integer>());

    Future<Integer> result =
      Source.from(Arrays.asList(1, 2, 3))
        .via(duplicator)
        .runFold(0, (n, sum) -> n + sum, mat);

    assertEquals(new Integer(12), Await.result(result, Duration.create(3, "seconds")));

  }

  //#simpler-one-to-many
  public class Duplicator2<A> extends GraphStage<FlowShape<A, A>> {

    public final Inlet<A> in = Inlet.create("Duplicator.in");
    public final Outlet<A> out = Outlet.create("Duplicator.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);

    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              A elem = grab(in);
              // this will temporarily suspend this handler until the two elems
              // are emitted and then reinstates it
              emitMultiple(out, Arrays.asList(elem, elem).iterator());
            }
          });

          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(in);
            }
          });
        }
      };
    }
  }
  //#simpler-one-to-many



  @Test
  public void demonstrateASimplerOneToManyStage() throws Exception {
    // tests:
    Graph<FlowShape<Integer, Integer>, BoxedUnit> duplicator =
            Flow.fromGraph(new Duplicator2<Integer>());

    Future<Integer> result =
            Source.from(Arrays.asList(1, 2, 3))
                    .via(duplicator)
                    .runFold(0, (n, sum) -> n + sum, mat);

    assertEquals(new Integer(12), Await.result(result, Duration.create(3, "seconds")));
  }

  @Test
  public void demonstrateChainingOfGraphStages() throws Exception {
    Graph<SinkShape<Integer>, Future<String>> sink = Sink.fold("", (acc, n) -> acc + n.toString());

    //#graph-stage-chain
    Future<String> resultFuture = Source.from(Arrays.asList(1,2,3,4,5))
            .via(new Filter<Integer>((n) -> n % 2 == 0))
            .via(new Duplicator<Integer>())
            .via(new Map<Integer, Integer>((n) -> n / 2))
            .runWith(sink, mat);

    //#graph-stage-chain

    assertEquals("1122", Await.result(resultFuture, Duration.create(3, "seconds")));
  }


  //#async-side-channel
  // will close upstream when the future completes
  public class KillSwitch<A> extends GraphStage<FlowShape<A, A>> {

    private final Future<BoxedUnit> switchF;

    public KillSwitch(Future<BoxedUnit> switchF) {
      this.switchF = switchF;
    }

    public final Inlet<A> in = Inlet.create("KillSwitch.in");
    public final Outlet<A> out = Outlet.create("KillSwitch.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);
    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              push(out, grab(in));
            }
          });
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(in);
            }
          });
        }

        @Override
        public void preStart() {
          AsyncCallback<BoxedUnit> callback = createAsyncCallback(new Procedure<BoxedUnit>() {
            @Override
            public void apply(BoxedUnit param) throws Exception {
              completeStage();
            }
          });

          ExecutionContext ec = system.dispatcher();
          switchF.onSuccess(new OnSuccess<BoxedUnit>() {
            @Override
            public void onSuccess(BoxedUnit result) throws Throwable {
              callback.invoke(BoxedUnit.UNIT);
            }
          }, ec);
        }
      };
    }
  }
  //#async-side-channel

  @Test
  public void demonstrateAnAsynchronousSideChannel() throws Exception{

    // tests:
    Promise<BoxedUnit> switchF = Futures.promise();
    Graph<FlowShape<Integer, Integer>, BoxedUnit> killSwitch =
      Flow.fromGraph(new KillSwitch<>(switchF.future()));

    ExecutionContext ec = system.dispatcher();

    // TODO this is probably racey, is there a way to make sure it happens after?
    Future<Integer> valueAfterKill = switchF.future().flatMap(new Mapper<BoxedUnit, Future<Integer>>() {
      @Override
      public Future<Integer> apply(BoxedUnit parameter) {
        return Futures.successful(4);
      }
    }, ec);


    Future<Integer> result =
      Source.from(Arrays.asList(1, 2, 3)).concat(Source.fromFuture(valueAfterKill))
        .via(killSwitch)
        .runFold(0, (n, sum) -> n + sum, mat);

    switchF.success(BoxedUnit.UNIT);

    assertEquals(new Integer(6), Await.result(result, Duration.create(3, "seconds")));
  }


  //#timed
  // each time an event is pushed through it will trigger a period of silence
  public class TimedGate<A> extends GraphStage<FlowShape<A, A>> {

    private final FiniteDuration silencePeriod;

    public TimedGate(FiniteDuration silencePeriod) {
      this.silencePeriod = silencePeriod;
    }

    public final Inlet<A> in = Inlet.create("TimedGate.in");
    public final Outlet<A> out = Outlet.create("TimedGate.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);
    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new TimerGraphStageLogic(shape) {

        private boolean open = false;

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              A elem = grab(in);
              if (open) pull(in);
              else {
                push(out, elem);
                open = true;
                scheduleOnce("key", silencePeriod);
              }
            }
          });
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(in);
            }
          });
        }

        @Override
        public void onTimer(Object key) {
          if (key.equals("key")) {
            open = false;
          }
        }
      };
    }
  }
  //#timed

  public void demonstrateAGraphStageWithATimer() throws Exception {
    // tests:
    Future<Integer> result =
      Source.from(Arrays.asList(1, 2, 3))
        .via(new TimedGate<>(Duration.create(2, "seconds")))
        .takeWithin(Duration.create(250, "millis"))
        .runFold(0, (n, sum) -> n + sum, mat);

    assertEquals(new Integer(1), Await.result(result, Duration.create(3, "seconds")));
  }


  //#materialized
  public class FirstValue<A> extends GraphStageWithMaterializedValue<FlowShape<A, A>, Future<A>> {

    public final Inlet<A> in = Inlet.create("FirstValue.in");
    public final Outlet<A> out = Outlet.create("FirstValue.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);
    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    @Override
    public Tuple2<GraphStageLogic, Future<A>> createLogicAndMaterializedValue(Attributes inheritedAttributes) {
      Promise<A> promise = Futures.promise();

      GraphStageLogic logic = new GraphStageLogic(shape) {
        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              A elem = grab(in);
              promise.success(elem);
              push(out, elem);

              // replace handler with one just forwarding
              setHandler(in, new AbstractInHandler() {
                @Override
                public void onPush() {
                  push(out, grab(in));
                }
              });
            }
          });

          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              pull(in);
            }
          });
        }
      };

      return new Tuple2(logic, promise.future());
    }
  }
  //#materialized

  public void demonstrateACustomMaterializedValue() throws Exception {
    // tests:
    RunnableGraph<Future<Integer>> flow = Source.from(Arrays.asList(1, 2, 3))
      .viaMat(new FirstValue(), Keep.right())
      .to(Sink.ignore());

    Future<Integer> result = flow.run(mat);

    assertEquals(new Integer(1), Await.result(result, Duration.create(3, "seconds")));
  }


  //#detached
  public class TwoBuffer<A> extends GraphStage<FlowShape<A, A>> {

    public final Inlet<A> in = Inlet.create("TwoBuffer.in");
    public final Outlet<A> out = Outlet.create("TwoBuffer.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);

    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {

        private final int SIZE = 2;
        private Queue<A> buffer = new ArrayDeque<>(SIZE);
        private boolean downstreamWaiting = false;

        private boolean isBufferFull() {
          return buffer.size() == SIZE;
        }

        @Override
        public void preStart() {
          // a detached stage needs to start upstream demand
          // itself as it is not triggered by downstream demand
          pull(in);
        }

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() {
              A elem = grab(in);
              buffer.add(elem);
              if (downstreamWaiting) {
                downstreamWaiting = false;
                A bufferedElem = buffer.poll();
                push(out, bufferedElem);
              }
              if (!isBufferFull()) {
                pull(in);
              }
            }

            @Override
            public void onUpstreamFinish() {
              if (!buffer.isEmpty()) {
                // emit the rest if possible
                emitMultiple(out, buffer.iterator());
              }
              completeStage();
            }
          });


          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              if (buffer.isEmpty()) {
                downstreamWaiting = true;
              } else {
                A elem = buffer.poll();
                push(out, elem);
              }
              if (!isBufferFull() && !hasBeenPulled(in)) {
                pull(in);
              }
            }
          });
        }
      };

    }
  }
  //#detached


  public void demonstrateADetachedGraphStage() throws Exception {
    // tests:
    Future<Integer> result1 = Source.from(Arrays.asList(1, 2, 3))
      .via(new TwoBuffer<>())
      .runFold(0, (acc, n) -> acc + n, mat);

    assertEquals(new Integer(6), Await.result(result1, Duration.create(3, "seconds")));

    TestSubscriber.ManualProbe<Integer> subscriber = TestSubscriber.manualProbe(system);
    TestPublisher.Probe<Integer> publisher = TestPublisher.probe(0, system);
    RunnableGraph<BoxedUnit> flow2 =
      Source.fromPublisher(publisher)
        .via(new TwoBuffer<>())
        .to(Sink.fromSubscriber(subscriber));

    flow2.run(mat);

    Subscription sub = subscriber.expectSubscription();
    // this happens even though the subscriber has not signalled any demand
    publisher.sendNext(1);
    publisher.sendNext(2);

    sub.cancel();
  }

}
