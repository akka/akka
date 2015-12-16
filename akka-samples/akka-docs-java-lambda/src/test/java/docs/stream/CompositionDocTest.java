/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import java.util.Arrays;

import akka.stream.ClosedShape;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.dispatch.Mapper;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.javadsl.Tcp.OutgoingConnection;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import scala.concurrent.*;
import scala.runtime.BoxedUnit;
import scala.Option;

public class CompositionDocTest {

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
  public void nonNestedFlow() throws Exception {
    //#non-nested-flow
    Source.single(0)
      .map(i -> i + 1)
      .filter(i -> i != 0)
      .map(i -> i - 2)
      .to(Sink.fold(0, (acc, i) -> acc + i));

    // ... where is the nesting?
    //#non-nested-flow
  }

  @Test
  public void nestedFlow() throws Exception {
    //#nested-flow
    final Source<Integer, BoxedUnit> nestedSource =
      Source.single(0) // An atomic source
        .map(i -> i + 1) // an atomic processing stage
        .named("nestedSource"); // wraps up the current Source and gives it a name

    final Flow<Integer, Integer, BoxedUnit> nestedFlow =
      Flow.of(Integer.class).filter(i -> i != 0) // an atomic processing stage
        .map(i -> i - 2) // another atomic processing stage
        .named("nestedFlow"); // wraps up the Flow, and gives it a name

    final Sink<Integer, BoxedUnit> nestedSink =
      nestedFlow.to(Sink.fold(0, (acc, i) -> acc + i)) // wire an atomic sink to the nestedFlow
        .named("nestedSink"); // wrap it up

    // Create a RunnableGraph
    final RunnableGraph<BoxedUnit> runnableGraph = nestedSource.to(nestedSink);
    //#nested-flow
  }

  @Test
  public void reusingComponents() throws Exception {
    final Source<Integer, BoxedUnit> nestedSource =
      Source.single(0) // An atomic source
        .map(i -> i + 1) // an atomic processing stage
        .named("nestedSource"); // wraps up the current Source and gives it a name

    final Flow<Integer, Integer, BoxedUnit> nestedFlow =
      Flow.of(Integer.class).filter(i -> i != 0) // an atomic processing stage
        .map(i -> i - 2) // another atomic processing stage
        .named("nestedFlow"); // wraps up the Flow, and gives it a name

    final Sink<Integer, BoxedUnit> nestedSink =
      nestedFlow.to(Sink.fold(0, (acc, i) -> acc + i)) // wire an atomic sink to the nestedFlow
        .named("nestedSink"); // wrap it up

    //#reuse
    // Create a RunnableGraph from our components
    final RunnableGraph<BoxedUnit> runnableGraph = nestedSource.to(nestedSink);

    // Usage is uniform, no matter if modules are composite or atomic
    final RunnableGraph<BoxedUnit> runnableGraph2 =
      Source.single(0).to(Sink.fold(0, (acc, i) -> acc + i));
    //#reuse
  }

  @Test
  public void complexGraph() throws Exception {
    //#complex-graph
    RunnableGraph.fromGraph(
      GraphDSL.create(builder -> {
      final Outlet<Integer> A = builder.add(Source.single(0)).out();
      final UniformFanOutShape<Integer, Integer> B = builder.add(Broadcast.create(2));
      final UniformFanInShape<Integer, Integer> C = builder.add(Merge.create(2));
      final FlowShape<Integer, Integer> D =
        builder.add(Flow.of(Integer.class).map(i -> i + 1));
      final UniformFanOutShape<Integer, Integer> E = builder.add(Balance.create(2));
      final UniformFanInShape<Integer, Integer> F = builder.add(Merge.create(2));
      final Inlet<Integer> G = builder.add(Sink.<Integer> foreach(System.out::println)).in();

      builder.from(F).toFanIn(C);
      builder.from(A).viaFanOut(B).viaFanIn(C).toFanIn(F);
      builder.from(B).via(D).viaFanOut(E).toFanIn(F);
      builder.from(E).toInlet(G);
      return ClosedShape.getInstance();
    }));
    //#complex-graph

    //#complex-graph-alt
    RunnableGraph.fromGraph(
      GraphDSL.create(builder -> {
      final SourceShape<Integer> A = builder.add(Source.single(0));
      final UniformFanOutShape<Integer, Integer> B = builder.add(Broadcast.create(2));
      final UniformFanInShape<Integer, Integer> C = builder.add(Merge.create(2));
      final FlowShape<Integer, Integer> D =
        builder.add(Flow.of(Integer.class).map(i -> i + 1));
      final UniformFanOutShape<Integer, Integer> E = builder.add(Balance.create(2));
      final UniformFanInShape<Integer, Integer> F = builder.add(Merge.create(2));
      final SinkShape<Integer> G = builder.add(Sink.foreach(System.out::println));

      builder.from(F.out()).toInlet(C.in(0));
      builder.from(A).toInlet(B.in());
      builder.from(B.out(0)).toInlet(C.in(1));
      builder.from(C.out()).toInlet(F.in(0));
      builder.from(B.out(1)).via(D).toInlet(E.in());
      builder.from(E.out(0)).toInlet(F.in(1));
      builder.from(E.out(1)).to(G);
      return ClosedShape.getInstance();
    }));
    //#complex-graph-alt
  }

  @Test
  public void partialGraph() throws Exception {
    //#partial-graph
    final Graph<FlowShape<Integer, Integer>, BoxedUnit> partial =
      GraphDSL.create(builder -> {
        final UniformFanOutShape<Integer, Integer> B = builder.add(Broadcast.create(2));
        final UniformFanInShape<Integer, Integer> C = builder.add(Merge.create(2));
        final UniformFanOutShape<Integer, Integer> E = builder.add(Balance.create(2));
        final UniformFanInShape<Integer, Integer> F = builder.add(Merge.create(2));

        builder.from(F.out()).toInlet(C.in(0));
        builder.from(B).viaFanIn(C).toFanIn(F);
        builder.from(B).via(builder.add(Flow.of(Integer.class).map(i -> i + 1))).viaFanOut(E).toFanIn(F);

        return new FlowShape<Integer, Integer>(B.in(), E.out(1));
      });

    //#partial-graph

    //#partial-use
    Source.single(0).via(partial).to(Sink.ignore());
    //#partial-use

    //#partial-flow-dsl
    // Convert the partial graph of FlowShape to a Flow to get
    // access to the fluid DSL (for example to be able to call .filter())
    final Flow<Integer, Integer, BoxedUnit> flow = Flow.fromGraph(partial);

    // Simple way to create a graph backed Source
    final Source<Integer, BoxedUnit> source = Source.fromGraph(
      GraphDSL.create(builder -> {
        final UniformFanInShape<Integer, Integer> merge = builder.add(Merge.create(2));
        builder.from(builder.add(Source.single(0))).toFanIn(merge);
        builder.from(builder.add(Source.from(Arrays.asList(2, 3, 4)))).toFanIn(merge);
        // Exposing exactly one output port
        return new SourceShape<Integer>(merge.out());
      })
    );

    // Building a Sink with a nested Flow, using the fluid DSL
    final Sink<Integer, BoxedUnit> sink = Flow.of(Integer.class)
      .map(i -> i * 2)
      .drop(10)
      .named("nestedFlow")
      .to(Sink.head());

    // Putting all together
    final RunnableGraph<BoxedUnit> closed = source.via(flow.filter(i -> i > 1)).to(sink);
    //#partial-flow-dsl
  }

  @Test
  public void closedGraph() throws Exception {
    //#embed-closed
    final RunnableGraph<BoxedUnit> closed1 =
      Source.single(0).to(Sink.foreach(System.out::println));
    final RunnableGraph<BoxedUnit> closed2 =
      RunnableGraph.fromGraph(
        GraphDSL.create(builder -> {
          final ClosedShape embeddedClosed = builder.add(closed1);
          return embeddedClosed; // Could return ClosedShape.getInstance()
    }));
    //#embed-closed
  }

  //#mat-combine-4a
  static class MyClass {
    private Promise<Option<Integer>> p;
    private OutgoingConnection conn;

    public MyClass(Promise<Option<Integer>> p, OutgoingConnection conn) {
      this.p = p;
      this.conn = conn;
    }

    public void close() {
      p.success(Option.empty());
    }
  }

  static class Combiner {
    static Future<MyClass> f(Promise<Option<Integer>> p,
        Pair<Future<OutgoingConnection>, Future<String>> rest) {
      return rest.first().map(new Mapper<OutgoingConnection, MyClass>() {
        public MyClass apply(OutgoingConnection c) {
          return new MyClass(p, c);
        }
      }, system.dispatcher());
    }
  }
  //#mat-combine-4a

  @Test
  public void materializedValues() throws Exception {
    //#mat-combine-1
    // Materializes to Promise<BoxedUnit>                                     (red)
    final Source<Integer, Promise<Option<Integer>>> source = Source.<Integer>maybe();

    // Materializes to BoxedUnit                                              (black)
    final Flow<Integer, Integer, BoxedUnit> flow1 = Flow.of(Integer.class).take(100);

    // Materializes to Promise<Option<>>                                     (red)
    final Source<Integer, Promise<Option<Integer>>> nestedSource =
      source.viaMat(flow1, Keep.left()).named("nestedSource");
      //#mat-combine-1

    //#mat-combine-2
    // Materializes to BoxedUnit                                              (orange)
    final Flow<Integer, ByteString, BoxedUnit> flow2 = Flow.of(Integer.class)
      .map(i -> ByteString.fromString(i.toString()));

    // Materializes to Future<OutgoingConnection>                             (yellow)
    final Flow<ByteString, ByteString, Future<OutgoingConnection>> flow3 =
      Tcp.get(system).outgoingConnection("localhost", 8080);

    // Materializes to Future<OutgoingConnection>                             (yellow)
    final Flow<Integer, ByteString, Future<OutgoingConnection>> nestedFlow =
      flow2.viaMat(flow3, Keep.right()).named("nestedFlow");
      //#mat-combine-2

    //#mat-combine-3
    // Materializes to Future<String>                                         (green)
    final Sink<ByteString, Future<String>> sink = Sink
      .fold("", (acc, i) -> acc + i.utf8String());

    // Materializes to Pair<Future<OutgoingConnection>, Future<String>>       (blue)
    final Sink<Integer, Pair<Future<OutgoingConnection>, Future<String>>> nestedSink =
      nestedFlow.toMat(sink, Keep.both());
      //#mat-combine-3

    //#mat-combine-4b
    // Materializes to Future<MyClass>                                        (purple)
    final RunnableGraph<Future<MyClass>> runnableGraph =
      nestedSource.toMat(nestedSink, Combiner::f);
    //#mat-combine-4b
  }

  @Test
  public void attributes() throws Exception {
    //#attributes-inheritance
    final Source<Integer, BoxedUnit> nestedSource =
      Source.single(0)
        .map(i -> i + 1)
        .named("nestedSource"); // Wrap, no inputBuffer set

    final Flow<Integer, Integer, BoxedUnit> nestedFlow =
      Flow.of(Integer.class).filter(i -> i != 0)
        .via(Flow.of(Integer.class)
          .map(i -> i - 2)
          .withAttributes(Attributes.inputBuffer(4, 4))) // override
        .named("nestedFlow"); // Wrap, no inputBuffer set

    final Sink<Integer, BoxedUnit> nestedSink =
      nestedFlow.to(Sink.fold(0, (acc, i) -> acc + i)) // wire an atomic sink to the nestedFlow
        .withAttributes(Attributes.name("nestedSink")
          .and(Attributes.inputBuffer(3, 3))); // override
    //#attributes-inheritance
  }

}
