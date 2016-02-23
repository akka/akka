/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpMethod;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.*;
import akka.http.javadsl.testkit.JUnitRouteTest;

import org.junit.Test;

public class MethodDirectivesExamplesTest extends JUnitRouteTest {
  @Test
  public void testDelete() {
    //#delete
    final Route route = delete(complete("This is a DELETE request."));

    testRoute(route).run(HttpRequest.DELETE("/")).assertEntity(
        "This is a DELETE request.");
    //#delete
  }

  @Test
  public void testGet() {
    //#get
    final Route route = get(complete("This is a GET request."));

    testRoute(route).run(HttpRequest.GET("/")).assertEntity(
        "This is a GET request.");
    //#get
  }

  @Test
  public void testHead() {
    //#head
    final Route route = head(complete("This is a HEAD request."));

    testRoute(route).run(HttpRequest.HEAD("/")).assertEntity(
        "This is a HEAD request.");
    //#head
  }

  @Test
  public void testOptions() {
    //#options
    final Route route = options(complete("This is a OPTIONS request."));

    testRoute(route).run(HttpRequest.OPTIONS("/")).assertEntity(
        "This is a OPTIONS request.");
    //#options
  }

  @Test
  public void testPatch() {
    //#patch
    final Route route = patch(complete("This is a PATCH request."));

    testRoute(route).run(HttpRequest.PATCH("/").withEntity("patch content"))
        .assertEntity("This is a PATCH request.");
    //#patch
  }

  @Test
  public void testPost() {
    //#post
    final Route route = post(complete("This is a POST request."));

    testRoute(route).run(HttpRequest.POST("/").withEntity("post content"))
        .assertEntity("This is a POST request.");
    //#post
  }

  @Test
  public void testPut() {
    //#put
    final Route route = put(complete("This is a PUT request."));

    testRoute(route).run(HttpRequest.PUT("/").withEntity("put content"))
        .assertEntity("This is a PUT request.");
    //#put
  }

  @Test
  public void testMethodExample() {
    //#method-example
    final Route route = method(HttpMethods.PUT,
        complete("This is a PUT request."));

    testRoute(route).run(HttpRequest.PUT("/").withEntity("put content"))
        .assertEntity("This is a PUT request.");

    testRoute(route).run(HttpRequest.GET("/")).assertStatusCode(
        StatusCodes.METHOD_NOT_ALLOWED);
    //#method-example
  }

  @Test
  public void testExtractMethodExample() {
    //#extractMethod
    final RequestVal<HttpMethod> requestMethod = RequestVals.requestMethod();

    final Route otherMethod = handleWith1(
        requestMethod,
        (ctx, method) -> ctx.complete("This " + method.value()
            + " request, clearly is not a GET!"));

    final Route route = route(get(complete("This is a GET request.")),
        otherMethod);

    testRoute(route).run(HttpRequest.GET("/")).assertEntity(
        "This is a GET request.");

    testRoute(route).run(HttpRequest.PUT("/").withEntity("put content"))
        .assertEntity("This PUT request, clearly is not a GET!");

    testRoute(route).run(HttpRequest.HEAD("/")).assertEntity(
        "This HEAD request, clearly is not a GET!");
    //#extractMethod
  }
}
