package docs.stream;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
//#imports
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.dispatch.OnSuccess;
import akka.japi.Option;
import akka.japi.Predicate;
import akka.japi.function.Procedure;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.stage.*;
//#imports
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.testkit.JavaTestKit;
import akka.japi.Function;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Subscription;
import scala.Tuple2;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class GraphStageDocTest extends AbstractJavaTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("GraphStageDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }


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
            public void onPull() throws Exception {
              push(out, counter);
              counter += 1;
            }
          });
        }

      };
    }

  }
  //#simple-source

  //#simple-sink
  public class StdoutSink extends GraphStage<SinkShape<Integer>> {
    public final Inlet<Integer> in = Inlet.create("StdoutSink.in");

    private final SinkShape<Integer> shape = SinkShape.of(in);
    @Override
    public SinkShape<Integer> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape()) {

        // This requests one element at the Sink startup.
        @Override
        public void preStart() {
          pull(in);
        }

        {
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() throws Exception {
              Integer element = grab(in);
              System.out.println(element);
              pull(in);
            }
          });
        }
      };
    }
  }
  //#simple-sink

  @Test
  public void demonstrateCustomSourceUsage() throws Exception {
    //#simple-source-usage
    // A GraphStage is a proper Graph, just like what GraphDSL.create would return
    Graph<SourceShape<Integer>, NotUsed> sourceGraph = new NumbersSource();

    // Create a Source from the Graph to access the DSL
    Source<Integer, NotUsed> mySource = Source.fromGraph(sourceGraph);

    // Returns 55
    CompletionStage<Integer> result1 = mySource.take(10).runFold(0, (sum, next) -> sum + next, mat);

    // The source is reusable. This returns 5050
    CompletionStage<Integer> result2 = mySource.take(100).runFold(0, (sum, next) -> sum + next, mat);
    //#simple-source-usage

    assertEquals(result1.toCompletableFuture().get(3, TimeUnit.SECONDS), (Integer) 55);
    assertEquals(result2.toCompletableFuture().get(3, TimeUnit.SECONDS), (Integer) 5050);
  }

  @Test
  public void demonstrateCustomSinkUsage() throws Exception {
    Graph<SinkShape<Integer>, NotUsed> sinkGraph = new StdoutSink();

    Sink<Integer, NotUsed> mySink = Sink.fromGraph(sinkGraph);

    Source.from(Arrays.asList(1, 2, 3)).runWith(mySink, mat);
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
            public void onPush() throws Exception {
              push(out, f.apply(grab(in)));
            }
          });
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() throws Exception {
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
    final Graph<FlowShape<String, Integer>, NotUsed> stringLength =
      Flow.fromGraph(new Map<String, Integer>(new Function<String, Integer>() {
        @Override
        public Integer apply(String str) {
          return str.length();
        }
      }));

    CompletionStage<Integer> result =
      Source.from(Arrays.asList("one", "two", "three"))
        .via(stringLength)
        .runFold(0, (sum, n) -> sum + n, mat);

    assertEquals(new Integer(11), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
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
            public void onPull() throws Exception {
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
    Graph<FlowShape<Integer, Integer>, NotUsed> evenFilter =
      Flow.fromGraph(new Filter<Integer>(n -> n % 2 == 0));

    CompletionStage<Integer> result =
      Source.from(Arrays.asList(1, 2, 3, 4, 5, 6))
        .via(evenFilter)
        .runFold(0, (elem, sum) -> sum + elem, mat);

    assertEquals(new Integer(12), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
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
            public void onPull() throws Exception {
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
    Graph<FlowShape<Integer, Integer>, NotUsed> duplicator =
            Flow.fromGraph(new Duplicator<Integer>());

    CompletionStage<Integer> result =
      Source.from(Arrays.asList(1, 2, 3))
        .via(duplicator)
        .runFold(0, (n, sum) -> n + sum, mat);

    assertEquals(new Integer(12), result.toCompletableFuture().get(3, TimeUnit.SECONDS));

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
            public void onPull() throws Exception {
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
    Graph<FlowShape<Integer, Integer>, NotUsed> duplicator =
            Flow.fromGraph(new Duplicator2<Integer>());

    CompletionStage<Integer> result =
            Source.from(Arrays.asList(1, 2, 3))
                    .via(duplicator)
                    .runFold(0, (n, sum) -> n + sum, mat);

    assertEquals(new Integer(12), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void demonstrateChainingOfGraphStages() throws Exception {
    Graph<SinkShape<Integer>, CompletionStage<String>> sink = Sink.fold("", (acc, n) -> acc + n.toString());

    //#graph-stage-chain
    CompletionStage<String> resultFuture = Source.from(Arrays.asList(1,2,3,4,5))
            .via(new Filter<Integer>((n) -> n % 2 == 0))
            .via(new Duplicator<Integer>())
            .via(new Map<Integer, Integer>((n) -> n / 2))
            .runWith(sink, mat);

    //#graph-stage-chain

    assertEquals("1122", resultFuture.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }


  //#async-side-channel
  // will close upstream in all materializations of the stage instance
  // when the completion stage completes
  public class KillSwitch<A> extends GraphStage<FlowShape<A, A>> {

    private final CompletionStage<Done> switchF;

    public KillSwitch(CompletionStage<Done> switchF) {
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
            public void onPull() throws Exception {
              pull(in);
            }
          });
        }

        @Override
        public void preStart() {
          AsyncCallback<Done> callback = createAsyncCallback(new Procedure<Done>() {
            @Override
            public void apply(Done param) throws Exception {
              completeStage();
            }
          });

          ExecutionContext ec = system.dispatcher();
          switchF.thenAccept(callback::invoke);
        }
      };
    }
  }
  //#async-side-channel

  @Test
  public void demonstrateAnAsynchronousSideChannel() throws Exception{

    // tests:
    TestSubscriber.Probe<Integer> out = TestSubscriber.probe(system);
    TestPublisher.Probe<Integer> in = TestPublisher.probe(0, system);

    CompletableFuture<Done> switchF = new CompletableFuture<>();
    Graph<FlowShape<Integer, Integer>, NotUsed> killSwitch =
      Flow.fromGraph(new KillSwitch<>(switchF));

    Source.fromPublisher(in).via(killSwitch).to(Sink.fromSubscriber(out)).run(mat);

    out.request(1);
    in.sendNext(1);
    out.expectNext(1);

    switchF.complete(Done.getInstance());

    out.expectComplete();
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
            public void onPush() throws Exception {
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
            public void onPull() throws Exception {
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
    CompletionStage<Integer> result =
      Source.from(Arrays.asList(1, 2, 3))
        .via(new TimedGate<>(Duration.create(2, "seconds")))
        .takeWithin(Duration.create(250, "millis"))
        .runFold(0, (n, sum) -> n + sum, mat);

    assertEquals(new Integer(1), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }


  //#materialized
  public class FirstValue<A> extends GraphStageWithMaterializedValue<FlowShape<A, A>, CompletionStage<A>> {

    public final Inlet<A> in = Inlet.create("FirstValue.in");
    public final Outlet<A> out = Outlet.create("FirstValue.out");

    private final FlowShape<A, A> shape = FlowShape.of(in, out);
    @Override
    public FlowShape<A, A> shape() {
      return shape;
    }

    @Override
    public Tuple2<GraphStageLogic, CompletionStage<A>> createLogicAndMaterializedValue(Attributes inheritedAttributes) {
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
            public void onPull() throws Exception {
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
    RunnableGraph<CompletionStage<Integer>> flow = Source.from(Arrays.asList(1, 2, 3))
      .viaMat(new FirstValue(), Keep.right())
      .to(Sink.ignore());

    CompletionStage<Integer> result = flow.run(mat);

    assertEquals(new Integer(1), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
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
            public void onPull() throws Exception {
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
    CompletionStage<Integer> result1 = Source.from(Arrays.asList(1, 2, 3))
      .via(new TwoBuffer<>())
      .runFold(0, (acc, n) -> acc + n, mat);

    assertEquals(new Integer(6), result1.toCompletableFuture().get(3, TimeUnit.SECONDS));

    TestSubscriber.ManualProbe<Integer> subscriber = TestSubscriber.manualProbe(system);
    TestPublisher.Probe<Integer> publisher = TestPublisher.probe(0, system);
    RunnableGraph<NotUsed> flow2 =
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
