/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import akka.NotUsed;
import docs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.ActorMaterializerSettings;
import akka.stream.Materializer;
import akka.stream.Supervision;
import akka.stream.javadsl.Flow;
import akka.stream.ActorAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.japi.function.Function;
import akka.testkit.JavaTestKit;

public class FlowErrorDocTest extends AbstractJavaTest {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
      system = ActorSystem.create("FlowDocTest");
  }

  @AfterClass
  public static void tearDown() {
      JavaTestKit.shutdownActorSystem(system);
      system = null;
  }
  
  @Test(expected = ExecutionException.class)
  public void demonstrateFailStream() throws Exception {
    //#stop
    final Materializer mat = ActorMaterializer.create(system);
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5))
      .map(elem -> 100 / elem);
    final Sink<Integer, CompletionStage<Integer>> fold =
      Sink.<Integer, Integer> fold(0, (acc, elem) -> acc + elem);
    final CompletionStage<Integer> result = source.runWith(fold, mat);
    // division by zero will fail the stream and the
    // result here will be a Future completed with Failure(ArithmeticException)
    //#stop

    result.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  @Test
  public void demonstrateResumeStream() throws Exception {
    //#resume
    final Function<Throwable, Supervision.Directive> decider = exc -> {
      if (exc instanceof ArithmeticException)
        return Supervision.resume();
      else
        return Supervision.stop();
    };
    final Materializer mat = ActorMaterializer.create(
      ActorMaterializerSettings.create(system).withSupervisionStrategy(decider),
      system);
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5))
      .map(elem -> 100 / elem);
    final Sink<Integer, CompletionStage<Integer>> fold =
      Sink.fold(0, (acc, elem) -> acc + elem);
    final CompletionStage<Integer> result = source.runWith(fold, mat);
    // the element causing division by zero will be dropped
    // result here will be a Future completed with Success(228)
    //#resume

    assertEquals(Integer.valueOf(228), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void demonstrateResumeSectionStream() throws Exception {
    //#resume-section
    final Materializer mat = ActorMaterializer.create(system);
    final Function<Throwable, Supervision.Directive> decider = exc -> {
      if (exc instanceof ArithmeticException)
        return Supervision.resume();
      else
        return Supervision.stop();
    };
    final Flow<Integer, Integer, NotUsed> flow =
        Flow.of(Integer.class).filter(elem -> 100 / elem < 50).map(elem -> 100 / (5 - elem))
        .withAttributes(ActorAttributes.withSupervisionStrategy(decider));
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5))
      .via(flow); 
    final Sink<Integer, CompletionStage<Integer>> fold =
      Sink.<Integer, Integer> fold(0, (acc, elem) -> acc + elem);
    final CompletionStage<Integer> result = source.runWith(fold, mat);
    // the elements causing division by zero will be dropped
    // result here will be a Future completed with Success(150)
    //#resume-section

    assertEquals(Integer.valueOf(150), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void demonstrateRestartSectionStream() throws Exception {
    //#restart-section
    final Materializer mat = ActorMaterializer.create(system);
    final Function<Throwable, Supervision.Directive> decider = exc -> {
      if (exc instanceof IllegalArgumentException)
        return Supervision.restart();
      else
        return Supervision.stop();
    };
    final Flow<Integer, Integer, NotUsed> flow =
      Flow.of(Integer.class).scan(0, (acc, elem) -> {
        if (elem < 0) throw new IllegalArgumentException("negative not allowed");
        else return acc + elem;
      })
      .withAttributes(ActorAttributes.withSupervisionStrategy(decider));
    final Source<Integer, NotUsed> source = Source.from(Arrays.asList(1, 3, -1, 5, 7))
      .via(flow);
    final CompletionStage<List<Integer>> result = source.grouped(1000)
      .runWith(Sink.<List<Integer>>head(), mat);
    // the negative element cause the scan stage to be restarted,
    // i.e. start from 0 again
    // result here will be a Future completed with Success(List(0, 1, 4, 0, 5, 12))
    //#restart-section
    
    assertEquals(
      Arrays.asList(0, 1, 4, 0, 5, 12), 
      result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

}
