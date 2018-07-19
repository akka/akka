/**
 *  Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Graph;
import akka.stream.Materializer;
import akka.stream.SinkShape;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import com.typesafe.config.ConfigFactory;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RecipeLastElements extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLastElements", ConfigFactory.parseString("akka.loglevel=DEBUG\nakka.loggers = [akka.testkit.TestEventListener]"));
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

      {

        //#last-elements
        final Graph<SinkShape<Integer>, CompletionStage<List<Integer>>> sink = Sink.fold(
                new ArrayList<Integer>(), (acc, n) -> {
                  acc.add(n);
                  return acc.stream()
                          .skip(Math.max(0, acc.size() - 3))
                          .collect(Collectors.toList());
                }
        );


        final CompletionStage<List<Integer>> f = Source.from(Arrays.asList(1, 2, 3, 4, 5))
                .runWith(sink, mat);
        //#last-elements

        final List<Integer> result = f
          .toCompletableFuture().get(3, TimeUnit.SECONDS);

        Assert.assertEquals(Arrays.asList(3, 4, 5), result);
      }
    };
  }
}
