/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Sink;
import jdocs.AbstractJavaTest;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class SinkRecipeDocTest extends AbstractJavaTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("SinkRecipeDocTest");
    mat = ActorMaterializer.create(system);
  }

  @Test
  public void foreachAsync() {
    final Function<Integer, CompletionStage<Void>> asyncProcessing =
        param -> CompletableFuture.completedFuture(param).thenAccept(System.out::println);

    // #forseachAsync-processing
    // final Function<Integer, CompletionStage<Void>> asyncProcessing = _

    final Source<Integer, NotUsed> numberSource = Source.range(1, 100);

    numberSource.runWith(Sink.foreachAsync(10, asyncProcessing), mat);
    // #forseachAsync-processing
  }
}
