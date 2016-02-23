/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.RequestVal;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.values.Headers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

public class HeaderRequestValsExampleTest extends JUnitRouteTest {

  @Test
  public void testHeaderVals() {
    //#by-class
    // extract the entire header instance:
    RequestVal<Host> host = Headers.byClass(Host.class).instance();

    final Route route =
      route(
        handleWith1(host, (ctx, h) ->
          ctx.complete(String.format("Host header was: %s", h.host()))
        )
      );

    // tests:
    final HttpRequest request =
      HttpRequest
        .GET("http://akka.io/")
      .addHeader(Host.create("akka.io"));
    testRoute(route).run(request).assertEntity("Host header was: akka.io");

    //#by-class
  }

  @Test
  public void testHeaderByName() {
    //#by-name
    // extract the `value` of the header:
    final RequestVal<String> XFishName = Headers.byName("X-Fish-Name").value();

    final Route route =
      route(
        handleWith1(XFishName, (ctx, xFishName) ->
          ctx.complete(String.format("The `X-Fish-Name` header's value was: %s", xFishName))
        )
      );

    // tests:
    final HttpRequest request =
      HttpRequest
        .GET("/")
        .addHeader(RawHeader.create("X-Fish-Name", "Blippy"));
    testRoute(route).run(request).assertEntity("The `X-Fish-Name` header's value was: Blippy");

    //#by-name
  }
}