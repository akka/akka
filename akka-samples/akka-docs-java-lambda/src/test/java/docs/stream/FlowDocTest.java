/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import akka.japi.Pair;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import scala.Option;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.dispatch.Futures;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;

public class FlowDocTest {

    private static ActorSystem system;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("FlowDocTest");
    }

    @AfterClass
    public static void tearDown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    final Materializer mat = ActorMaterializer.create(system);

    @Test
    public void sourceIsImmutable() throws Exception {
        //#source-immutable
        final Source<Integer, BoxedUnit> source =
            Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        source.map(x -> 0); // has no effect on source, since it's immutable
        source.runWith(Sink.fold(0, (agg, next) -> agg + next), mat); // 55

        // returns new Source<Integer>, with `map()` appended
        final Source<Integer, BoxedUnit> zeroes = source.map(x -> 0);
        final Sink<Integer, Future<Integer>> fold =
            Sink.fold(0, (agg, next) -> agg + next);
        zeroes.runWith(fold, mat); // 0
        //#source-immutable

        int result = Await.result(
            zeroes.runWith(fold, mat),
            Duration.create(3, TimeUnit.SECONDS)
        );
        assertEquals(0, result);
    }

    @Test
    public void materializationInSteps() throws Exception {
        //#materialization-in-steps
        final Source<Integer, BoxedUnit> source =
            Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        // note that the Future is scala.concurrent.Future
        final Sink<Integer, Future<Integer>> sink =
            Sink.fold(0, (aggr, next) -> aggr + next);

        // connect the Source to the Sink, obtaining a RunnableFlow
        final RunnableGraph<Future<Integer>> runnable =
            source.toMat(sink, Keep.right());

        // materialize the flow
        final Future<Integer> sum = runnable.run(mat);
        //#materialization-in-steps

        int result = Await.result(sum, Duration.create(3, TimeUnit.SECONDS));
        assertEquals(55, result);
    }

    @Test
    public void materializationRunWith() throws Exception {
        //#materialization-runWith
        final Source<Integer, BoxedUnit> source =
            Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        final Sink<Integer, Future<Integer>> sink =
            Sink.fold(0, (aggr, next) -> aggr + next);

        // materialize the flow, getting the Sinks materialized value
        final Future<Integer> sum = source.runWith(sink, mat);
        //#materialization-runWith

        int result = Await.result(sum, Duration.create(3, TimeUnit.SECONDS));
        assertEquals(55, result);
    }

    @Test
    public void materializedMapUnique() throws Exception {
        //#stream-reuse
        // connect the Source to the Sink, obtaining a RunnableGraph
        final Sink<Integer, Future<Integer>> sink =
          Sink.fold(0, (aggr, next) -> aggr + next);
        final RunnableGraph<Future<Integer>> runnable =
            Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).toMat(sink, Keep.right());

        // get the materialized value of the FoldSink
        final Future<Integer> sum1 = runnable.run(mat);
        final Future<Integer> sum2 = runnable.run(mat);

        // sum1 and sum2 are different Futures!
        //#stream-reuse

        int result1 = Await.result(sum1, Duration.create(3, TimeUnit.SECONDS));
        assertEquals(55, result1);
        int result2 = Await.result(sum2, Duration.create(3, TimeUnit.SECONDS));
        assertEquals(55, result2);
    }

    @Test
    @SuppressWarnings("unused")
    public void compoundSourceCannotBeUsedAsKey() throws Exception {
        //#compound-source-is-not-keyed-runWith
        final Object tick = new Object();

        final FiniteDuration oneSecond = Duration.create(1, TimeUnit.SECONDS);
        //akka.actor.Cancellable
        final Source<Object, Cancellable> timer =
            Source.tick(oneSecond, oneSecond, tick);

        Sink.ignore().runWith(timer, mat);

        final Source<String, Cancellable> timerMap = timer.map(t -> "tick");
        // WRONG: returned type is not the timers Cancellable!
        // Cancellable timerCancellable = Sink.ignore().runWith(timerMap, mat);
        //#compound-source-is-not-keyed-runWith

        //#compound-source-is-not-keyed-run
        // retain the materialized map, in order to retrieve the timer's Cancellable
        final Cancellable timerCancellable = timer.to(Sink.ignore()).run(mat);
        timerCancellable.cancel();
        //#compound-source-is-not-keyed-run
    }

    @Test
    public void creatingSourcesSinks() throws Exception {
        //#source-sink
        // Create a source from an Iterable
        List<Integer> list = new LinkedList<Integer>();
        list.add(1);
        list.add(2);
        list.add(3);
        Source.from(list);

        // Create a source form a Future
        Source.from(Futures.successful("Hello Streams!"));

        // Create a source from a single element
        Source.single("only one element");

        // an empty source
        Source.empty();

        // Sink that folds over the stream and returns a Future
        // of the final result in the MaterializedMap
        Sink.fold(0, (Integer aggr, Integer next) -> aggr + next);

        // Sink that returns a Future in the MaterializedMap,
        // containing the first element of the stream
        Sink.head();

        // A Sink that consumes a stream without doing anything with the elements
        Sink.ignore();

        // A Sink that executes a side-effecting call for every element of the stream
        Sink.foreach(System.out::println);
        //#source-sink
    }

    @Test
    public void variousWaysOfConnecting() throws Exception {
      //#flow-connecting
      // Explicitly creating and wiring up a Source, Sink and Flow
      Source.from(Arrays.asList(1, 2, 3, 4))
        .via(Flow.of(Integer.class).map(elem -> elem * 2))
        .to(Sink.foreach(System.out::println));

      // Starting from a Source
      final Source<Integer, BoxedUnit> source = Source.from(Arrays.asList(1, 2, 3, 4))
          .map(elem -> elem * 2);
      source.to(Sink.foreach(System.out::println));

      // Starting from a Sink
      final Sink<Integer, BoxedUnit> sink = Flow.of(Integer.class)
          .map(elem -> elem * 2).to(Sink.foreach(System.out::println));
      Source.from(Arrays.asList(1, 2, 3, 4)).to(sink);
      //#flow-connecting
    }

  @Test
  public void transformingMaterialized() throws Exception {

    FiniteDuration oneSecond = FiniteDuration.apply(1, TimeUnit.SECONDS);
    Flow<Integer, Integer, Cancellable> throttler =
      Flow.fromGraph(FlowGraph.create(
        Source.tick(oneSecond, oneSecond, ""),
        (b, tickSource) -> {
          FanInShape2<String, Integer, Integer> zip = b.add(ZipWith.create(Keep.right()));
          b.from(tickSource).toInlet(zip.in0());
          return FlowShape.of(zip.in1(), zip.out());
        }));

    //#flow-mat-combine

    // An empty source that can be shut down explicitly from the outside
    Source<Integer, Promise<Option<Integer>>> source = Source.<Integer>maybe();

    // A flow that internally throttles elements to 1/second, and returns a Cancellable
    // which can be used to shut down the stream
    Flow<Integer, Integer, Cancellable> flow = throttler;

    // A sink that returns the first element of a stream in the returned Future
    Sink<Integer, Future<Integer>> sink = Sink.head();


    // By default, the materialized value of the leftmost stage is preserved
    RunnableGraph<Promise<Option<Integer>>> r1 = source.via(flow).to(sink);

    // Simple selection of materialized values by using Keep.right
    RunnableGraph<Cancellable> r2 = source.viaMat(flow, Keep.right()).to(sink);
    RunnableGraph<Future<Integer>> r3 = source.via(flow).toMat(sink, Keep.right());

    // Using runWith will always give the materialized values of the stages added
    // by runWith() itself
    Future<Integer> r4 = source.via(flow).runWith(sink, mat);
    Promise<Option<Integer>> r5 = flow.to(sink).runWith(source, mat);
    Pair<Promise<Option<Integer>>, Future<Integer>> r6 = flow.runWith(source, sink, mat);

    // Using more complext combinations
    RunnableGraph<Pair<Promise<Option<Integer>>, Cancellable>> r7 =
    source.viaMat(flow, Keep.both()).to(sink);

    RunnableGraph<Pair<Promise<Option<Integer>>, Future<Integer>>> r8 =
    source.via(flow).toMat(sink, Keep.both());

    RunnableGraph<Pair<Pair<Promise<Option<Integer>>, Cancellable>, Future<Integer>>> r9 =
    source.viaMat(flow, Keep.both()).toMat(sink, Keep.both());

    RunnableGraph<Pair<Cancellable, Future<Integer>>> r10 =
    source.viaMat(flow, Keep.right()).toMat(sink, Keep.both());

    // It is also possible to map over the materialized values. In r9 we had a
    // doubly nested pair, but we want to flatten it out


    RunnableGraph<Cancellable> r11 =
    r9.mapMaterializedValue( (nestedTuple) -> {
      Promise<Option<Integer>> p = nestedTuple.first().first();
      Cancellable c = nestedTuple.first().second();
      Future<Integer> f = nestedTuple.second();

      // Picking the Cancellable, but we could  also construct a domain class here
      return c;
    });
    //#flow-mat-combine
  }



}
