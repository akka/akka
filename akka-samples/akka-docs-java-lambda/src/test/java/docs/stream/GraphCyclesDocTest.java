package docs.stream;

import java.util.Arrays;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Broadcast;
import akka.stream.javadsl.Concat;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.FlowGraph;
import akka.stream.javadsl.Merge;
import akka.stream.javadsl.MergePreferred;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Zip2With;
import akka.stream.javadsl.ZipWith;
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
  
  final FlowMaterializer mat = ActorFlowMaterializer.create(system);
  
  final Source<Integer> source = Source.from(Arrays.asList(1, 2, 3, 4, 5));
  
  @Test
  public void demonstrateDeadlockedCycle() {
    //#deadlocked
    // WARNING! The graph below deadlocks!
    final Merge<Integer> merge = Merge.create();
    final Broadcast<Integer> bcast = Broadcast.create();
    
    final Flow<Integer, Integer> printFlow = 
      Flow.of(Integer.class).map(s -> {
        System.out.println(s); 
        return s;
      });
    
    FlowGraph.builder()
      .allowCycles()
      .addEdge(source, merge)
      .addEdge(merge, printFlow, bcast)
      .addEdge(bcast, Sink.ignore())
      .addEdge(bcast, merge)
      .build();
    //#deadlocked
  }
  
  @Test
  public void demonstrateUnfairCycle() {
    //#unfair
    // WARNING! The graph below stops consuming from "source" after a few steps
    final MergePreferred<Integer> merge = MergePreferred.create();
    final Broadcast<Integer> bcast = Broadcast.create();
    
    final Flow<Integer, Integer> printFlow = 
        Flow.of(Integer.class).map(s -> {
          System.out.println(s); 
          return s;
        });
    
    FlowGraph.builder()
      .allowCycles()
      .addEdge(source, merge)
      .addEdge(merge, printFlow, bcast)
      .addEdge(bcast, Sink.ignore())
      .addEdge(bcast, merge.preferred())
      .build();
    //#unfair
  }

  @Test
  public void demonstrateDroppingCycle() {
    //#dropping
    final Merge<Integer> merge = Merge.create();
    final Broadcast<Integer> bcast = Broadcast.create();
    
    final Flow<Integer, Integer> printFlow = 
      Flow.of(Integer.class).map(s -> {
        System.out.println(s); 
        return s;
      });
    
    FlowGraph.builder()
      .allowCycles()
      .addEdge(source, merge)
      .addEdge(merge, printFlow, bcast)
      .addEdge(bcast, Sink.ignore())
      .addEdge(bcast, Flow.of(Integer.class).buffer(10, OverflowStrategy.dropHead()), merge)
      .build();
    //#dropping
  }
  
  @Test
  public void demonstrateZippingCycle() {
    //#zipping-dead
    // WARNING! The graph below never processes any elements
    final Broadcast<Integer> bcast = Broadcast.create();
    final Zip2With<Integer, Integer, Integer> zip = 
      ZipWith.create((left, right) -> right);
    
    final Flow<Integer, Integer> printFlow = 
        Flow.of(Integer.class).map(s -> {
          System.out.println(s); 
          return s;
        });
    
    FlowGraph.builder()
      .allowCycles()
      .addEdge(source, zip.left())
      .addEdge(zip.out(), printFlow, bcast)
      .addEdge(bcast, Sink.ignore())
      .addEdge(bcast, zip.right())
      .build();
    //#zipping-dead
  }

  @Test
  public void demonstrateLiveZippingCycle() {
    //#zipping-live
    final Broadcast<Integer> bcast = Broadcast.create();
    final Concat<Integer> concat = Concat.create();
    final Zip2With<Integer, Integer, Integer> zip = 
        ZipWith.create((left, right) -> left);
    
    final Flow<Integer, Integer> printFlow = 
        Flow.of(Integer.class).map(s -> {
          System.out.println(s); 
          return s;
        });
    
    FlowGraph.builder()
      .allowCycles()
      .addEdge(source, zip.left())
      .addEdge(zip.out(), printFlow, bcast)
      .addEdge(bcast, Sink.ignore())
      .addEdge(bcast, concat.second())
      .addEdge(concat.out(), zip.right())
      .addEdge(Source.single(0), concat.first())
      .build();
    //#zipping-live
  }
     
}
