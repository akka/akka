/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.StatusCodes;
import akka.japi.pf.PFBuilder;
import akka.pattern.CircuitBreaker;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import static akka.http.javadsl.server.PathMatchers.*;
import static scala.compat.java8.JFunction.func;

public class FutureDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testOnComplete() {
    //#onComplete
    // import static scala.compat.java8.JFunction.func;
    // import static akka.http.javadsl.server.PathMatchers.*;

    final Route route = path(segment("divide").slash(integerSegment()).slash(integerSegment()),
      (a, b) -> onComplete(
        () -> CompletableFuture.supplyAsync(() -> a / b),
        maybeResult -> maybeResult
          .map(func(result -> complete("The result was " + result)))
          .recover(new PFBuilder<Throwable, Route>()
            .matchAny(ex -> complete(StatusCodes.InternalServerError(),
              "An error occurred: " + ex.getMessage())
            )
            .build())
          .get()
      )
    );

    testRoute(route).run(HttpRequest.GET("/divide/10/2"))
      .assertEntity("The result was 5");

    testRoute(route).run(HttpRequest.GET("/divide/10/0"))
      .assertStatusCode(StatusCodes.InternalServerError())
      .assertEntity("An error occurred: / by zero");
    //#onComplete
  }

  @Test
  public void testOnSuccess() {
    //#onSuccess
    final Route route = path("success", () ->
      onSuccess(() -> CompletableFuture.supplyAsync(() -> "Ok"),
        extraction -> complete(extraction)
      )
    ).orElse(path("failure", () ->
      onSuccess(() -> CompletableFuture.supplyAsync(() -> {
          throw new RuntimeException();
        }),
        extraction -> complete("never reaches here"))
    ));

    testRoute(route).run(HttpRequest.GET("/success"))
      .assertEntity("Ok");

    testRoute(route).run(HttpRequest.GET("/failure"))
      .assertStatusCode(StatusCodes.InternalServerError())
      .assertEntity("There was an internal server error.");
    //#onSuccess
  }

  @Test
  public void testCompleteOrRecoverWith() {
    //#completeOrRecoverWith
    final Route route = path("success", () ->
      completeOrRecoverWith(
        () -> CompletableFuture.supplyAsync(() -> "Ok"),
        Marshaller.stringToEntity(),
        extraction -> failWith(extraction) // not executed
      )
    ).orElse(path("failure", () ->
      completeOrRecoverWith(
        () -> CompletableFuture.supplyAsync(() -> {
          throw new RuntimeException();
        }),
        Marshaller.stringToEntity(),
        extraction -> failWith(extraction))
    ));

    testRoute(route).run(HttpRequest.GET("/success"))
      .assertEntity("Ok");

    testRoute(route).run(HttpRequest.GET("/failure"))
      .assertStatusCode(StatusCodes.InternalServerError())
      .assertEntity("There was an internal server error.");
    //#completeOrRecoverWith
  }
  
  @Test
  public void testOnCompleteWithBreaker() throws InterruptedException {
    //#onCompleteWithBreaker
    // import static scala.compat.java8.JFunction.func;
    // import static akka.http.javadsl.server.PathMatchers.*;

    final int maxFailures = 1;
    final FiniteDuration callTimeout = FiniteDuration.create(5, TimeUnit.SECONDS);
    final FiniteDuration resetTimeout = FiniteDuration.create(1, TimeUnit.SECONDS);
    final CircuitBreaker breaker = CircuitBreaker.create(system().scheduler(), maxFailures, callTimeout, resetTimeout);
    
    final Route route = path(segment("divide").slash(integerSegment()).slash(integerSegment()),
      (a, b) -> onCompleteWithBreaker(breaker,
        () ->  CompletableFuture.supplyAsync(() -> a / b),
        maybeResult -> maybeResult
          .map(func(result -> complete("The result was " + result)))
          .recover(new PFBuilder<Throwable, Route>()
            .matchAny(ex -> complete(StatusCodes.InternalServerError(),
              "An error occurred: " + ex.getMessage())
            )
            .build())
          .get()
      )
    );

    testRoute(route).run(HttpRequest.GET("/divide/10/2"))
      .assertEntity("The result was 5");

    testRoute(route).run(HttpRequest.GET("/divide/10/0"))
      .assertStatusCode(StatusCodes.InternalServerError())
      .assertEntity("An error occurred: / by zero");
    // opened the circuit-breaker 
    
    testRoute(route).run(HttpRequest.GET("/divide/10/0"))
          .assertStatusCode(StatusCodes.ServiceUnavailable())
          .assertEntity("The server is currently unavailable (because it is overloaded or down for maintenance).");

    Thread.sleep(resetTimeout.toMillis() + 300);
    // circuit breaker resets after this time
    
    testRoute(route).run(HttpRequest.GET("/divide/8/2"))
      .assertEntity("The result was 4");
    
    //#onCompleteWithBreaker
  }

}
