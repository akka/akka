/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.testkit.JUnitRouteTest;
import java.util.function.Function;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.Location;

public class SchemeDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testScheme() {
    //#extractScheme
    final Route route = extractScheme((scheme) -> 
                                      complete(String.format("The scheme is '%s'", scheme)));
    testRoute(route).run(HttpRequest.GET("https://www.example.com/"))
      .assertEntity("The scheme is 'https'");
    //#extractScheme
  }

  @Test
  public void testRedirection() {
    //#scheme
    final Route route = route(
      scheme("http", ()->
        extract((ctx) -> ctx.getRequest().getUri(), (uri)->
          redirect(uri.scheme("https"), StatusCodes.MOVED_PERMANENTLY)
        )
      ),
      scheme("https", ()->
        complete("Safe and secure!")
      )
    );

    testRoute(route).run(HttpRequest.GET("http://www.example.com/hello"))
      .assertStatusCode(StatusCodes.MOVED_PERMANENTLY)
      .assertHeaderExists(Location.create("https://www.example.com/hello"))
    ;

    testRoute(route).run(HttpRequest.GET("https://www.example.com/hello"))
      .assertEntity("Safe and secure!");
    //#scheme
  }

}
