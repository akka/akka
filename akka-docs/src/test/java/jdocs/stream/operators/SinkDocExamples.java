/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.Tuple2;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SinkDocExamples {

    private final static ActorSystem system = ActorSystem.create("SourceFromExample");
    private final static Materializer materializer = ActorMaterializer.create(system);

    static void reduceExample() throws InterruptedException, ExecutionException, TimeoutException {

        //#reduce-operator-example
        Source<Integer, NotUsed> ints = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        CompletionStage<Integer> sum = ints.runWith(Sink.reduce((a, b) -> a + b), materializer);
        int result = sum.toCompletableFuture().get(3, TimeUnit.SECONDS);
        System.out.println(result);
        // 55
        //#reduce-operator-example
    }

    static void takeLastExample() throws InterruptedException, ExecutionException, TimeoutException {
        //#takeLast-operator-example
        // tuple of (Name, GPA)
        List<Tuple2> sortedStudents = Arrays.asList(new Tuple2("Benita", 2.1), new Tuple2("Adrian", 3.1),
                new Tuple2("Alexis", 4), new Tuple2("Kendra", 4.2), new Tuple2("Jerrie", 4.3), new Tuple2("Alison", 4.7));

        Source<Tuple2, NotUsed> studentSource = Source.from(sortedStudents);

        CompletionStage<List<Tuple2>> topThree = studentSource.runWith(Sink.takeLast(3), materializer);

        List<Tuple2> result = topThree.toCompletableFuture().get(3, TimeUnit.SECONDS);

        System.out.println("#### Top students ####");
        for (int i = result.size() - 1; i >= 0; i--) {
            Tuple2<String, Double> s = result.get(i);
            System.out.println("Name: " + s._1 + ", " + "GPA: " + s._2);
        }
        /*
        #### Top students ####
        Name: Alison, GPA: 4.7
        Name: Jerrie, GPA: 4.3
        Name: Kendra, GPA: 4.2
      */
      //#takeLast-operator-example
    }

    static void lastExample() throws InterruptedException, ExecutionException, TimeoutException {
        //#last-operator-example
        Source<Integer, NotUsed> source = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        CompletionStage<Integer> result = source.runWith(Sink.last(), materializer);
        int lastItem = result.toCompletableFuture().get(3, TimeUnit.SECONDS);
        System.out.println(lastItem);
        // 10
        //#last-operator-example
    }

}