/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

import static akka.http.javadsl.server.Directives.*;
import static akka.http.javadsl.common.PartialApplication.*;

public class ComposingDirectivesTest extends JUnitRouteTest {

  @Test
  public void testAnyOf0Arg() {
    TestRoute getOrPost = testRoute(path("hello", () ->
      anyOf(this::get, this::post, () ->
        complete("hi"))));

    getOrPost
      .run(HttpRequest.GET("/hello"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("hi");

    getOrPost
      .run(HttpRequest.POST("/hello"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("hi");

    getOrPost
      .run(HttpRequest.PUT("/hello"))
      .assertStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
  }

  @Test
  public void testAnyOf1Arg() {
    TestRoute someParam = testRoute(path("param", () ->
      anyOf(bindParameter(this::parameter, "foo"), bindParameter(this::parameter, "bar"), (String param) -> complete("param is " + param)))
    );

    someParam
      .run(HttpRequest.GET("/param?foo=foz"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("param is foz");

    someParam
      .run(HttpRequest.GET("/param?bar=baz"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("param is baz");

    someParam
      .run(HttpRequest.GET("/param?charlie=alice"))
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testAllOf0Arg() {
    TestRoute charlie = testRoute(allOf(
      bindParameter(this::pathPrefix, "alice"),
      bindParameter(this::path, "bob"),
      () -> complete("Charlie!")));

    charlie.run(HttpRequest.GET("/alice/bob"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Charlie!");

    charlie.run(HttpRequest.GET("/alice"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    charlie.run(HttpRequest.GET("/bob"))
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testAllOf1Arg() {
    TestRoute extractTwo = testRoute(path("extractTwo", () ->
      allOf(this::extractScheme, this::extractMethod, (scheme, method) -> complete("You did a " + method.name() + " using " + scheme))
    ));

    extractTwo
      .run(HttpRequest.GET("/extractTwo"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a GET using http");

    extractTwo
      .run(HttpRequest.PUT("/extractTwo"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a PUT using http");
  }

  @Test
  public void testAllOf0And1Arg() {
    TestRoute route = testRoute(allOf(bindParameter(this::pathPrefix, "guess"), this::extractMethod, method -> complete("You did a " + method.name())));

    route
      .run(HttpRequest.GET("/guess"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a GET");

    route
      .run(HttpRequest.POST("/guess"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a POST");
  }

}
