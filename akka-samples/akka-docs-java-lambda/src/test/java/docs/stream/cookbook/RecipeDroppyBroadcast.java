/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.UniformFanOutShape;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import scala.concurrent.Future;
import scala.runtime.BoxedUnit;

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

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      //#droppy-bcast
      // Makes a sink drop elements if too slow
      public <T> Sink<T, Future<BoxedUnit>> droppySink(Sink<T, Future<BoxedUnit>> sink, int size) {
        return Flow.<T> create()
          .buffer(size, OverflowStrategy.dropHead())
          .toMat(sink, Keep.right());
      }
      //#droppy-bcast

      {
        final List<Integer> nums = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
          nums.add(i + 1);
        }

        final Sink<Integer, Future<BoxedUnit>> mySink1 = Sink.ignore();
        final Sink<Integer, Future<BoxedUnit>> mySink2 = Sink.ignore();
        final Sink<Integer, Future<BoxedUnit>> mySink3 = Sink.ignore();

        final Source<Integer, BoxedUnit> myData = Source.from(nums);

        //#droppy-bcast2
        RunnableGraph.fromGraph(FlowGraph.create(builder -> {
          final int outputCount = 3;
          final UniformFanOutShape<Integer, Integer> bcast =
            builder.add(Broadcast.create(outputCount));
          builder.from(builder.add(myData)).toFanOut(bcast);
          builder.from(bcast).to(builder.add(droppySink(mySink1, 10)));
          builder.from(bcast).to(builder.add(droppySink(mySink2, 10)));
          builder.from(bcast).to(builder.add(droppySink(mySink3, 10)));
          return ClosedShape.getInstance();
        }));
        //#droppy-bcast2
      }
    };
  }

}
