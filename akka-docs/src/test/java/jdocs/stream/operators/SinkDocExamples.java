/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.NotUsed;
import akka.actor.ActorSystem;

import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
// #takeLast-operator-example
import akka.japi.Pair;
// #takeLast-operator-example
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class SinkDocExamples {

  private static final ActorSystem system = ActorSystem.create("SourceFromExample");
  private static final Materializer materializer = ActorMaterializer.create(system);

  static void reduceExample() throws InterruptedException, ExecutionException, TimeoutException {

    // #reduce-operator-example
    Source<Integer, NotUsed> ints = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
    CompletionStage<Integer> sum = ints.runWith(Sink.reduce((a, b) -> a + b), materializer);
    sum.thenAccept(System.out::println);
    // 55
    // #reduce-operator-example
  }

  static void takeLastExample() throws InterruptedException, ExecutionException, TimeoutException {
    // #takeLast-operator-example
    // pair of (Name, GPA)
    List<Pair> sortedStudents =
        Arrays.asList(
            new Pair<>("Benita", 2.1),
            new Pair<>("Adrian", 3.1),
            new Pair<>("Alexis", 4),
            new Pair<>("Kendra", 4.2),
            new Pair<>("Jerrie", 4.3),
            new Pair<>("Alison", 4.7));

    Source<Pair, NotUsed> studentSource = Source.from(sortedStudents);

    CompletionStage<List<Pair>> topThree = studentSource.runWith(Sink.takeLast(3), materializer);

    topThree.thenAccept(
        result -> {
          System.out.println("#### Top students ####");
          for (int i = result.size() - 1; i >= 0; i--) {
            Pair<String, Double> s = result.get(i);
            System.out.println("Name: " + s.first() + ", " + "GPA: " + s.second());
          }
        });
    /*
      #### Top students ####
      Name: Alison, GPA: 4.7
      Name: Jerrie, GPA: 4.3
      Name: Kendra, GPA: 4.2
    */
    // #takeLast-operator-example
  }

  static void lastExample() throws InterruptedException, ExecutionException, TimeoutException {
    // #last-operator-example
    Source<Integer, NotUsed> source = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
    CompletionStage<Integer> result = source.runWith(Sink.last(), materializer);
    result.thenAccept(System.out::println);
    // 10
    // #last-operator-example
  }

  static void lastOptionExample()
      throws InterruptedException, ExecutionException, TimeoutException {
    // #lastOption-operator-example
    Source<Integer, NotUsed> source = Source.empty();
    CompletionStage<Optional<Integer>> result = source.runWith(Sink.lastOption(), materializer);
    result.thenAccept(System.out::println);
    // Optional.empty
    // #lastOption-operator-example
  }
}
