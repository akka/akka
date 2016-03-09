/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;

public class HeaderRequestValsExampleTest extends JUnitRouteTest {

  @Test
  public void testHeaderVals() {
    //#by-class

    final Route route =
      extractHost(host ->
        complete(String.format("Host header was: %s", host))
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

    final Route route =
      // extract the `value` of the header:
      headerValueByName("X-Fish-Name", xFishName ->
        complete(String.format("The `X-Fish-Name` header's value was: %s", xFishName))
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