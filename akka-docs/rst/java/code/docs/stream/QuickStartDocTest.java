/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream;

//#stream-imports
import akka.stream.*;
import akka.stream.javadsl.*;
//#stream-imports

//#other-imports
import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.util.ByteString;

import java.nio.file.Paths;
import java.math.BigInteger;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;
//#other-imports

import org.junit.*;

/**
 * This class is not meant to be run as a test in the test suite, but it
 * is set up such that it can be run interactively from within an IDE.
 */
public class QuickStartDocTest {
  
  static
  //#create-materializer
  final ActorSystem system = ActorSystem.create("QuickStart");
  final Materializer materializer = ActorMaterializer.create(system);
  //#create-materializer

  @AfterClass
  public static void teardown() {
    system.terminate();
  }
  
  @Test
  public void demonstrateSource() throws InterruptedException, ExecutionException {
    //#create-source
    final Source<Integer, NotUsed> source = Source.range(1, 100);
    //#create-source
    
    //#run-source
    source.runForeach(i -> System.out.println(i), materializer);
    //#run-source
    
    //#transform-source
    final Source<BigInteger, NotUsed> factorials =
      source
        .scan(BigInteger.ONE, (acc, next) -> acc.multiply(BigInteger.valueOf(next)));
    
    final CompletionStage<IOResult> result =
      factorials
        .map(num -> ByteString.fromString(num.toString() + "\n"))
        .runWith(FileIO.toPath(Paths.get("factorials.txt")), materializer);
    //#transform-source

    //#use-transformed-sink
    factorials.map(BigInteger::toString).runWith(lineSink("factorial2.txt"), materializer);
    //#use-transformed-sink
    
    //#add-streams
    final CompletionStage<Done> done =
      factorials
        .zipWith(Source.range(0, 99), (num, idx) -> String.format("%d! = %s", idx, num))
        .throttle(1, Duration.create(1, TimeUnit.SECONDS), 1, ThrottleMode.shaping())
        //#add-streams
        .take(2)
        //#add-streams
        .runForeach(s -> System.out.println(s), materializer);
    //#add-streams
    
    done.toCompletableFuture().get();
  }
  
  //#transform-sink
  public Sink<String, CompletionStage<IOResult>> lineSink(String filename) {
    return Flow.of(String.class)
      .map(s -> ByteString.fromString(s.toString() + "\n"))
      .toMat(FileIO.toPath(Paths.get(filename)), Keep.right());
  }
  //#transform-sink
  
}
