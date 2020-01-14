/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.source;

// #sourceFromCompletionStage
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

import akka.NotUsed;
import akka.Done;
import akka.actor.typed.ActorSystem;
import akka.stream.javadsl.*;

// #sourceFromCompletionStage

class FromCompletionStage {

  public static void sourceFromCompletionStage() {
    // Use one ActorSystem per application
    ActorSystem system = null;

    // #sourceFromCompletionStage
    CompletionStage<Integer> stage = CompletableFuture.completedFuture(10);

    Source<Integer, NotUsed> source = Source.completionStage(stage);

    Sink<Integer, CompletionStage<Done>> sink = Sink.foreach(i -> System.out.println(i.toString()));

    source.runWith(sink, system); // 10
    // #sourceFromCompletionStage
  }
}
