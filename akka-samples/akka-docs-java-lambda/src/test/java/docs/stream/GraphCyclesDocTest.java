package docs.stream;

import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.runtime.BoxedUnit;
import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.scaladsl.MergePreferred.MergePreferredShape;
import akka.testkit.JavaTestKit;


public class GraphCyclesDocTest {

  static ActorSystem system;


  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("GraphCyclesDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  final static SilenceSystemOut.System System = SilenceSystemOut.get();

  final Source<Integer, BoxedUnit> source = Source.from(Arrays.asList(1, 2, 3, 4, 5));

  @Test
  public void demonstrateDeadlockedCycle() {
    //#deadlocked
    // WARNING! The graph below deadlocks!
    final Flow<Integer, Integer, BoxedUnit> printFlow =
      Flow.of(Integer.class).map(s -> {
        System.out.println(s);
        return s;
      });

    FlowGraph.factory().closed(b -> {
      final UniformFanInShape<Integer, Integer> merge = b.graph(Merge.create(2));
      final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.create(2));

      final Outlet<Integer> src = b.source(source);
      final FlowShape<Integer, Integer> printer = b.graph(printFlow);
      final SinkShape<Integer> ignore = b.graph(Sink.ignore());
      
      b.from(src).viaFanIn(merge).via(printer).viaFanOut(bcast).to(ignore);
                      b.to(merge)            .fromFanOut(bcast);
    });
    //#deadlocked
  }

  @Test
  public void demonstrateUnfairCycle() {
    final Flow<Integer, Integer, BoxedUnit> printFlow =
        Flow.of(Integer.class).map(s -> {
          System.out.println(s);
          return s;
        });
    //#unfair
    // WARNING! The graph below stops consuming from "source" after a few steps
    FlowGraph.factory().closed(b -> {
      final MergePreferredShape<Integer> merge = b.graph(MergePreferred.create(1));
      final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.create(2));

      final Outlet<Integer> src = b.source(source);
      final FlowShape<Integer, Integer> printer = b.graph(printFlow);
      final SinkShape<Integer> ignore = b.graph(Sink.ignore());
      
      b.from(src).viaFanIn(merge).via(printer).viaFanOut(bcast).to(ignore);
                      b.to(merge.preferred()).fromFanOut(bcast);
    });
    //#unfair
  }

  @Test
  public void demonstrateDroppingCycle() {
    final Flow<Integer, Integer, BoxedUnit> printFlow =
        Flow.of(Integer.class).map(s -> {
          System.out.println(s);
          return s;
        });
    //#dropping
    FlowGraph.factory().closed(b -> {
      final UniformFanInShape<Integer, Integer> merge = b.graph(Merge.create(2));
      final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.create(2));
      final FlowShape<Integer, Integer> droppyFlow = b.graph(
          Flow.of(Integer.class).buffer(10, OverflowStrategy.dropHead()));

      final Outlet<Integer> src = b.source(source);
      final FlowShape<Integer, Integer> printer = b.graph(printFlow);
      final SinkShape<Integer> ignore = b.graph(Sink.ignore());
      
      b.from(src).viaFanIn(merge).via(printer).viaFanOut(bcast).to(ignore);
                   b.to(merge).via(droppyFlow).fromFanOut(bcast);
    });
    //#dropping
  }

  @Test
  public void demonstrateZippingCycle() {
    final Flow<Integer, Integer, BoxedUnit> printFlow =
        Flow.of(Integer.class).map(s -> {
          System.out.println(s);
          return s;
        });
    //#zipping-dead
    // WARNING! The graph below never processes any elements
    FlowGraph.factory().closed(b -> {
      final FanInShape2<Integer, Integer, Integer>
        zip = b.graph(ZipWith.create((Integer left, Integer right) -> left));
      final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.create(2));

      final FlowShape<Integer, Integer> printer = b.graph(printFlow);
      final SinkShape<Integer> ignore = b.graph(Sink.ignore());

      b.from(b.graph(source)).toInlet(zip.in0());
      b.from(zip.out()).via(printer).viaFanOut(bcast).to(ignore);
        b.to(zip.in1())            .fromFanOut(bcast);
    });
    //#zipping-dead
  }

  @Test
  public void demonstrateLiveZippingCycle() {
    final Flow<Integer, Integer, BoxedUnit> printFlow =
        Flow.of(Integer.class).map(s -> {
          System.out.println(s);
          return s;
        });
    //#zipping-live
    FlowGraph.factory().closed(b -> {
      final FanInShape2<Integer, Integer, Integer>
        zip = b.graph(ZipWith.create((Integer left, Integer right) -> left));
      final UniformFanOutShape<Integer, Integer> bcast = b.graph(Broadcast.create(2));
      final UniformFanInShape<Integer, Integer> concat = b.graph(Concat.create());

      final FlowShape<Integer, Integer> printer = b.graph(printFlow);
      final SinkShape<Integer> ignore = b.graph(Sink.ignore());

      b.from(b.graph(source)).toInlet(zip.in0());
      b.from(zip.out()).via(printer).viaFanOut(bcast).to(ignore);
        b.to(zip.in1()).viaFanIn(concat).from(b.graph(Source.single(1)));
                            b.to(concat)  .fromFanOut(bcast);
    });
    //#zipping-live
  }

}
