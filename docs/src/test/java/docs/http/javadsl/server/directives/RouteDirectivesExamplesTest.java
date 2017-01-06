/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Rejections;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.Collections;

public class RouteDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testComplete() {
    //#complete
    final Route route = route(
      path("a", () -> complete(HttpResponse.create().withEntity("foo"))),
      path("b", () -> complete(StatusCodes.OK)),
      path("c", () -> complete(StatusCodes.CREATED, "bar")),
      path("d", () -> complete(StatusCodes.get(201), "bar")),
      path("e", () ->
        complete(StatusCodes.CREATED,
                 Collections.singletonList(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8)),
                 HttpEntities.create("bar"))),
      path("f", () ->
        complete(StatusCodes.get(201),
                 Collections.singletonList(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8)),
                 HttpEntities.create("bar"))),
      path("g", () -> complete("baz"))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/a"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("foo");

    testRoute(route).run(HttpRequest.GET("/b"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK");

    testRoute(route).run(HttpRequest.GET("/c"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/d"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/e"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertHeaderExists(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8))
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/f"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertHeaderExists(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8))
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/g"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("baz");
    //#complete
  }

  @Test
  public void testReject() {
    //#reject
    final Route route = route(
      path("a", this::reject), // don't handle here, continue on
      path("a", () -> complete("foo")),
      path("b", () -> reject(Rejections.validationRejection("Restricted!")))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/a"))
      .assertEntity("foo");

    runRouteUnSealed(route, HttpRequest.GET("/b"))
      .assertRejections(Rejections.validationRejection("Restricted!"));
    //#reject
  }

  @Test
  public void testRedirect() {
    //#redirect
    final Route route = pathPrefix("foo", () ->
      route(
        pathSingleSlash(() -> complete("yes")),
        pathEnd(() -> redirect(Uri.create("/foo/"), StatusCodes.PERMANENT_REDIRECT))
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo/"))
      .assertEntity("yes");

    testRoute(route).run(HttpRequest.GET("/foo"))
      .assertStatusCode(StatusCodes.PERMANENT_REDIRECT)
      .assertEntity("The request, and all future requests should be repeated using <a href=\"/foo/\">this URI</a>.");
    //#redirect
  }

  @Test
  public void testFailWith() {
    //#failWith
    final Route route = path("foo", () ->
      failWith(new RuntimeException("Oops."))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo"))
      .assertStatusCode(StatusCodes.INTERNAL_SERVER_ERROR)
      .assertEntity("There was an internal server error.");
    //#failWith
  }
}
