/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

//#imports
//#range-imports
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;
//#range-imports

import java.util.Arrays;

//#imports

public class SourceDocExamples {

    public static void fromExample() {
        //#source-from-example
        final ActorSystem system = ActorSystem.create("SourceFromExample");
        final Materializer materializer = ActorMaterializer.create(system);

        Source<Integer, NotUsed> ints = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5));
        ints.runForeach(System.out::println, materializer);

        String text = "Perfection is finally attained not when there is no longer more to add," +
                "but when there is no longer anything to take away.";
        Source<String, NotUsed> words = Source.from(Arrays.asList(text.split("\\s")));
        words.runForeach(System.out::println, materializer);
        //#source-from-example
    }

    static void rangeExample() {

        final ActorSystem system = ActorSystem.create("Source");
        final Materializer materializer = ActorMaterializer.create(system);

        //#range

        Source<Integer, NotUsed> source = Source.range(1, 100);

        //#range

        //#range
        Source<Integer, NotUsed> sourceStepFive = Source.range(1, 100, 5);

        //#range

        //#range
        Source<Integer, NotUsed> sourceStepNegative = Source.range(100, 1, -1);
        //#range

        //#run-range
        source.runForeach(i -> System.out.println(i), materializer);
        //#run-range
    }

}
