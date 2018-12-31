/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

public class RecipeDroppyBroadcast extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeDroppyBroadcast");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void work() throws Exception {
    new TestKit(system) {
      //#droppy-bcast
      // Makes a sink drop elements if too slow
      public <T> Sink<T, CompletionStage<Done>> droppySink(Sink<T, CompletionStage<Done>> sink, int size) {
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

        final Sink<Integer, CompletionStage<Done>> mySink1 = Sink.ignore();
        final Sink<Integer, CompletionStage<Done>> mySink2 = Sink.ignore();
        final Sink<Integer, CompletionStage<Done>> mySink3 = Sink.ignore();

        final Source<Integer, NotUsed> myData = Source.from(nums);

        //#droppy-bcast2
        RunnableGraph.fromGraph(GraphDSL.create(builder -> {
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
