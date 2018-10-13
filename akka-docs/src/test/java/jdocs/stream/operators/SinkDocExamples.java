/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SinkDocExamples {

  static void reduceExample() throws InterruptedException, ExecutionException, TimeoutException {

    final ActorSystem system = ActorSystem.create("SourceFromExample");
    final Materializer materializer = ActorMaterializer.create(system);
    // #reduce-operator-example
    Source<Integer, NotUsed> ints = Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
    CompletionStage<Integer> sum = ints.runWith(Sink.reduce((a, b) -> a + b), materializer);
    int result = sum.toCompletableFuture().get(3, TimeUnit.SECONDS);
    System.out.println(result);
    // 55
    // #reduce-operator-example
  }
}
