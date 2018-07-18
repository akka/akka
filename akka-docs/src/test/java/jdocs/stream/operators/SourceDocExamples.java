/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

//#imports
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;

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

}
