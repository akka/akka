/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.testkit;

import java.util.function.Function;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;
import akka.http.javadsl.testkit.*;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.server.Rejections;

public class JUnitRouteTestTest extends JUnitRouteTest {

  @Test
  public void testTheMostSimpleAndDirectRouteTest() {
    TestRoute route =
      testRoute(
        complete(HttpResponse.create())
      );

    route.run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.OK);
  }

  @Test
  public void testUsingADirectiveAndSomeChecks() {
    RawHeader pinkHeader = RawHeader.create("Fancy", "pink");

    TestRoute route =
      testRoute(
        respondWithHeader(pinkHeader, () ->
          complete("abc")
        )
      );

    route.run(HttpRequest.GET("/").addHeader(pinkHeader))
      .assertStatusCode(StatusCodes.OK)
      .assertContentType("text/plain; charset=UTF-8")
      .assertEntity("abc")
      .assertHeaderExists("Fancy", "pink");
  }

  @Test
  public void testProperRejectionCollection() {
    TestRoute route =
      testRoute(
        get(() ->
          complete("naah")
        ).orElse(
          put(() ->
            complete("naah")
          )
        )
      );

    route.runWithRejections(HttpRequest.POST("/abc").withEntity("content"))
      .assertRejections(
        Rejections.method(HttpMethods.GET),
        Rejections.method(HttpMethods.PUT)
      );
  }

  @Test
  public void testSeparationOfRouteExecutionFromChecking() {
    RawHeader pinkHeader = RawHeader.create("Fancy", "pink");
    CompletableFuture<String> promise = new CompletableFuture<>();
    TestRoute route =
      testRoute(
        respondWithHeader(pinkHeader, () ->
          onSuccess(
            promise,
            result -> complete(result)
          )
        )
      );

    TestRouteResult result = route.run(HttpRequest.GET("/").addHeader(pinkHeader));

    promise.complete("abc");

    result
      .assertStatusCode(StatusCodes.OK)
      .assertContentType("text/plain; charset=UTF-8")
      .assertEntity("abc")
      .assertHeaderExists("Fancy", "pink");
  }
}
