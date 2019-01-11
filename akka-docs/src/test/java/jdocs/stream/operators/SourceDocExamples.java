/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

// #imports
// #range-imports
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;
// #range-imports

// #actor-ref-imports
import akka.actor.ActorRef;
import akka.actor.Status.Success;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Sink;
// #actor-ref-imports

import java.util.Arrays;

// #imports

public class SourceDocExamples {

  public static void fromExample() {
    // #source-from-example
    final ActorSystem system = ActorSystem.create("SourceFromExample");
    final Materializer materializer = ActorMaterializer.create(system);

    Source<Integer, NotUsed> ints = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5));
    ints.runForeach(System.out::println, materializer);

    String text =
        "Perfection is finally attained not when there is no longer more to add,"
            + "but when there is no longer anything to take away.";
    Source<String, NotUsed> words = Source.from(Arrays.asList(text.split("\\s")));
    words.runForeach(System.out::println, materializer);
    // #source-from-example
  }

  static void rangeExample() {

    final ActorSystem system = ActorSystem.create("Source");
    final Materializer materializer = ActorMaterializer.create(system);

    // #range

    Source<Integer, NotUsed> source = Source.range(1, 100);

    // #range

    // #range
    Source<Integer, NotUsed> sourceStepFive = Source.range(1, 100, 5);

    // #range

    // #range
    Source<Integer, NotUsed> sourceStepNegative = Source.range(100, 1, -1);
    // #range

    // #run-range
    source.runForeach(i -> System.out.println(i), materializer);
    // #run-range
  }

  static void actorRef() {
    // #actor-ref

    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = ActorMaterializer.create(system);

    int bufferSize = 100;
    Source<Object, ActorRef> source = Source.actorRef(bufferSize, OverflowStrategy.dropHead());

    ActorRef actorRef = source.to(Sink.foreach(System.out::println)).run(materializer);
    actorRef.tell("hello", ActorRef.noSender());
    actorRef.tell("hello", ActorRef.noSender());

    // The stream completes successfully with the following message
    actorRef.tell(new Success("completes stream"), ActorRef.noSender());
    // #actor-ref
  }
}
