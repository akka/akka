/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import static akka.http.javadsl.server.PathMatchers.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.StringUnmarshallers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

public class HandlerExampleDocTest extends JUnitRouteTest {
  @Test
  public void testSimpleHandler() {
    //#simple-handler-example-full
    class TestHandler extends akka.http.javadsl.server.AllDirectives {
      //#simple-handler
      Route handlerString = extractMethod(method ->
        extractUri(uri ->
          complete(String.format("This was a %s request to %s", method.name(), uri))
        )
      );

      Route handlerResponse = extractMethod(method ->
        extractUri(uri -> {
          // with full control over the returned HttpResponse:
          final HttpResponse response = HttpResponse.create()
            .withEntity(String.format("Accepted %s request to %s", method.name(), uri))
            .withStatus(StatusCodes.ACCEPTED);
          return complete(response);
        })
      );
      //#simple-handler

      Route createRoute() {
        return route(
          get(() ->
            handlerString
          ),
          post(() ->
            path("abc", () ->
              handlerResponse
            )
          )
        );
      }
    }

    // actual testing code
    TestRoute r = testRoute(new TestHandler().createRoute());
    r.run(HttpRequest.GET("/test"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("This was a GET request to http://example.com/test");

    r.run(HttpRequest.POST("/test"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    r.run(HttpRequest.POST("/abc"))
      .assertStatusCode(StatusCodes.ACCEPTED)
      .assertEntity("Accepted POST request to http://example.com/abc");
    //#simple-handler-example-full
  }

  @Test
  public void testCalculator() {
    //#handler2-example-full
    class TestHandler extends akka.http.javadsl.server.AllDirectives {

      final Route multiplyXAndYParam =
        parameter(StringUnmarshallers.INTEGER, "x", x ->
          parameter(StringUnmarshallers.INTEGER, "y", y ->
            complete("x * y = " + (x * y))
          )
        );

      final Route pathMultiply =
        path(integerSegment().slash(integerSegment()), (x, y) ->
          complete("x * y = " + (x * y))
        );

      Route subtract(int x, int y) {
        return complete("x - y = " + (x - y));
      }

      //#handler2

      Route createRoute() {
        return route(
          get(() ->
            pathPrefix("calculator", () -> route(
              path("multiply", () ->
                multiplyXAndYParam
              ),
              pathPrefix("path-multiply", () ->
                pathMultiply
              ),
              // handle by lifting method
              path(segment("subtract").slash(integerSegment()).slash(integerSegment()), this::subtract)
            ))
          )
        );
      }
    }

    // actual testing code
    TestRoute r = testRoute(new TestHandler().createRoute());
    r.run(HttpRequest.GET("/calculator/multiply?x=12&y=42"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("x * y = 504");

    r.run(HttpRequest.GET("/calculator/path-multiply/23/5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("x * y = 115");

    r.run(HttpRequest.GET("/calculator/subtract/42/12"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("x - y = 30");
    //#handler2-example-full
  }

  @Test
  public void testDeferredResultAsyncHandler() {
    //#async-example-full
    //#async-service-definition
    class CalculatorService {
      public CompletionStage<Integer> multiply(final int x, final int y) {
        return CompletableFuture.supplyAsync(() -> x * y);
      }

      public CompletionStage<Integer> add(final int x, final int y) {
        return CompletableFuture.supplyAsync(() -> x + y);
      }
    }
    //#async-service-definition

    class TestHandler extends akka.http.javadsl.server.AllDirectives {

      /**
       * Returns a route that applies the (required) request parameters "x" and "y", as integers, to
       * the inner function.
       */
      Route paramXY(BiFunction<Integer, Integer, Route> inner) {
        return
          parameter(StringUnmarshallers.INTEGER, "x", x ->
            parameter(StringUnmarshallers.INTEGER, "y", y ->
              inner.apply(x, y)
            )
          );
      }


      //#async-handler-1
      // would probably be injected or passed at construction time in real code
      CalculatorService calculatorService = new CalculatorService();

      public CompletionStage<Route> multiplyAsync(Executor ctx, int x, int y) {
        CompletionStage<Integer> result = calculatorService.multiply(x, y);
        return result.thenApplyAsync(product -> complete("x * y = " + product), ctx);
      }

      Route multiplyAsyncRoute =
        extractExecutionContext(ctx ->
          path("multiply", () ->
            paramXY((x, y) ->
              onSuccess(() -> multiplyAsync(ctx, x, y), Function.identity())
            )
          )
        );
      //#async-handler-1

      //#async-handler-2

      public Route addAsync(int x, int y) {
        CompletionStage<Integer> result = calculatorService.add(x, y);

        return onSuccess(() -> result, sum -> complete("x + y = " + sum));
      }

      Route addAsyncRoute =
        path("add", () ->
          paramXY(this::addAsync)
        );
      //#async-handler-2

      Route createRoute() {
        return route(
          get(() ->
            pathPrefix("calculator", () -> route(
              multiplyAsyncRoute,
              addAsyncRoute
            ))
          )
        );
      }
    }

    // testing code
    TestRoute r = testRoute(new TestHandler().createRoute());
    r.run(HttpRequest.GET("/calculator/multiply?x=12&y=42"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("x * y = 504");

    r.run(HttpRequest.GET("/calculator/add?x=23&y=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("x + y = 28");
    //#async-example-full
  }
}
