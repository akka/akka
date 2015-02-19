/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RecipeDroppyBroadcast extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      //#droppy-bcast
      // Makes a sink drop elements if too slow
      public <T> Sink<T> droppySink(Sink<T> sink, int bufferSize) {
        return Flow.<T>create().buffer(bufferSize, OverflowStrategy.dropHead()).to(sink);
      }
      //#droppy-bcast

      {

        final List<Integer> nums = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
          nums.add(i + 1);
        }

        final Sink<Integer> mySink1 = Sink.ignore();
        final Sink<Integer> mySink2= Sink.ignore();
        final Sink<Integer> mySink3 = Sink.ignore();

        final Source<Integer> myData = Source.from(nums);

        //#droppy-bcast
        final Broadcast<Integer> bcast = Broadcast.create();

        final FlowGraph g = new FlowGraphBuilder()
          .addEdge(myData, bcast)
          .addEdge(bcast, droppySink(mySink1, 10))
          .addEdge(bcast, droppySink(mySink2, 10))
          .addEdge(bcast, droppySink(mySink3, 10))
          .build();
        //#droppy-bcast
      }
    };
  }

}
