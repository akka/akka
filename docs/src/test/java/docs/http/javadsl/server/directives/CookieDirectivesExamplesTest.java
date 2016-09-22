/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Cookie;
import akka.http.javadsl.model.headers.HttpCookie;
import akka.http.javadsl.model.headers.SetCookie;
import akka.http.javadsl.server.Rejections;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.DateTime;
import org.junit.Test;

import java.util.Optional;
import java.util.OptionalLong;

public class CookieDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testCookie() {
    //#cookie
    final Route route = cookie("userName", nameCookie ->
      complete("The logged in user is '" + nameCookie.value() + "'")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeader(Cookie.create("userName", "paul")))
      .assertEntity("The logged in user is 'paul'");
    // missing cookie
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections(Rejections.missingCookie("userName"));
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("Request is missing required cookie 'userName'");
    //#cookie
  }

  @Test
  public void testOptionalCookie() {
    //#optionalCookie
    final Route route = optionalCookie("userName", optNameCookie -> {
      if (optNameCookie.isPresent()) {
        return complete("The logged in user is '" + optNameCookie.get().value() + "'");
      } else {
        return complete("No user logged in");
      }
    }
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeader(Cookie.create("userName", "paul")))
      .assertEntity("The logged in user is 'paul'");
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("No user logged in");
    //#optionalCookie
  }

  @Test
  public void testDeleteCookie() {
    //#deleteCookie
    final Route route = deleteCookie("userName", () ->
      complete("The user was logged out")
    );

    // tests:
    final HttpHeader expected = SetCookie.create(
      HttpCookie.create(
        "userName",
        "deleted",
        Optional.of(DateTime.MinValue()),
        OptionalLong.empty(),
        Optional.empty(),
        Optional.empty(),
        false,
        false,
        Optional.empty()));

    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("The user was logged out")
      .assertHeaderExists(expected);
    //#deleteCookie
  }

  @Test
  public void testSetCookie() {
    //#setCookie
    final Route route = setCookie(HttpCookie.create("userName", "paul"), () ->
      complete("The user was logged in")
    );

    // tests:
    final HttpHeader expected = SetCookie.create(HttpCookie.create("userName", "paul"));

    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("The user was logged in")
      .assertHeaderExists(expected);
    //#setCookie
  }

}
