/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import java.util.Optional;
import java.util.function.Function;

import org.junit.Test;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.model.headers.HttpOrigin;
import akka.http.javadsl.model.headers.HttpOriginRange;
import akka.http.javadsl.model.headers.Origin;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.japi.JavaPartialFunction;
import akka.http.javadsl.testkit.TestRoute;
import scala.PartialFunction;

public class HeaderDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testHeaderValue() {
    //#headerValue
    final Function<HttpHeader, Optional<Host>> extractHostPort = header -> {
      if (header instanceof Host) {
        return Optional.of((Host) header);
      } else {
        return Optional.empty();
      }
    };

    final Route route = headerValue(extractHostPort, host ->
        complete("The port was " + host.port())
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("example.com", 5043)))
                    .assertEntity("The port was 5043");

    testRoute(route).run(HttpRequest.GET("/"))
                    .assertStatusCode(StatusCodes.NOT_FOUND)
                    .assertEntity("The requested resource could not be found.");
    //#headerValue
  }

  @Test
  public void testHeaderValueByName() {
    //#headerValueByName
    final Route route = headerValueByName("X-User-Id", userId ->
        complete("The user is " + userId)
    );

    // tests:
    final RawHeader header = RawHeader.create("X-User-Id", "Joe42");
    testRoute(route).run(HttpRequest.GET("/").addHeader(header))
                    .assertEntity("The user is Joe42");

    testRoute(route).run(HttpRequest.GET("/"))
                    .assertStatusCode(StatusCodes.BAD_REQUEST)
                    .assertEntity("Request is missing required HTTP header 'X-User-Id'");
    //#headerValueByName
  }

  @Test
  public void testHeaderValueByType() {
    //#headerValueByType
    final Route route = headerValueByType(Origin.class, origin ->
        complete("The first origin was " + origin.getOrigins().iterator().next())
    );

    // tests:
    final Host host = Host.create("localhost", 8080);
    final Origin originHeader = Origin.create(HttpOrigin.create("http", host));

    testRoute(route).run(HttpRequest.GET("abc").addHeader(originHeader))
                    .assertEntity("The first origin was http://localhost:8080");

    testRoute(route).run(HttpRequest.GET("abc"))
                    .assertStatusCode(StatusCodes.BAD_REQUEST)
                    .assertEntity("Request is missing required HTTP header 'Origin'");
    //#headerValueByType
  }

  @Test
  public void testHeaderValuePF() {
    //#headerValuePF
    final PartialFunction<HttpHeader, Integer> extractHostPort =
        new JavaPartialFunction<HttpHeader, Integer>() {
      @Override
      public Integer apply(HttpHeader x, boolean isCheck) throws Exception {
        if (x instanceof Host) {
          if (isCheck) {
            return null;
          } else {
            return ((Host) x).port();
          }
        } else {
          throw noMatch();
        }
      }
    };

    final Route route = headerValuePF(extractHostPort, port ->
        complete("The port was " + port)
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("example.com", 5043)))
                    .assertEntity("The port was 5043");

    testRoute(route).run(HttpRequest.GET("/"))
                    .assertStatusCode(StatusCodes.NOT_FOUND)
                    .assertEntity("The requested resource could not be found.");
    //#headerValuePF
  }

  @Test
  public void testOptionalHeaderValue() {
    //#optionalHeaderValue
    final Function<HttpHeader, Optional<Integer>> extractHostPort = header -> {
      if (header instanceof Host) {
        return Optional.of(((Host) header).port());
      } else {
        return Optional.empty();
      }
    };

    final Route route = optionalHeaderValue(extractHostPort, port -> {
      if (port.isPresent()) {
        return complete("The port was " + port.get());
      } else {
        return complete("The port was not provided explicitly");
      }
    });

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("example.com", 5043)))
                    .assertEntity("The port was 5043");

    testRoute(route).run(HttpRequest.GET("/"))
                    .assertEntity("The port was not provided explicitly");
    //#optionalHeaderValue
  }

  @Test
  public void testOptionalHeaderValueByName() {
    //#optionalHeaderValueByName
    final Route route = optionalHeaderValueByName("X-User-Id", userId -> {
      if (userId.isPresent()) {
        return complete("The user is " + userId.get());
      } else {
        return complete("No user was provided");
      }
    });

    // tests:
    final RawHeader header = RawHeader.create("X-User-Id", "Joe42");
    testRoute(route).run(HttpRequest.GET("/").addHeader(header))
                    .assertEntity("The user is Joe42");

    testRoute(route).run(HttpRequest.GET("/")).assertEntity("No user was provided");
    //#optionalHeaderValueByName
  }

  @Test
  public void testOptionalHeaderValueByType() {
    //#optionalHeaderValueByType
    final Route route = optionalHeaderValueByType(Origin.class, origin -> {
      if (origin.isPresent()) {
        return complete("The first origin was " + origin.get().getOrigins().iterator().next());
      } else {
        return complete("No Origin header found.");
      }
    });

    // tests:

    // extract Some(header) if the type is matching
    Host host = Host.create("localhost", 8080);
    Origin originHeader = Origin.create(HttpOrigin.create("http", host));
    testRoute(route).run(HttpRequest.GET("abc").addHeader(originHeader))
                    .assertEntity("The first origin was http://localhost:8080");

    // extract None if no header of the given type is present
    testRoute(route).run(HttpRequest.GET("abc")).assertEntity("No Origin header found.");

    //#optionalHeaderValueByType
  }

  @Test
  public void testOptionalHeaderValuePF() {
    //#optionalHeaderValuePF
    final PartialFunction<HttpHeader, Integer> extractHostPort =
        new JavaPartialFunction<HttpHeader, Integer>() {
      @Override
      public Integer apply(HttpHeader x, boolean isCheck) throws Exception {
        if (x instanceof Host) {
          if (isCheck) {
            return null;
          } else {
            return ((Host) x).port();
          }
        } else {
          throw noMatch();
        }
      }
    };

    final Route route = optionalHeaderValuePF(extractHostPort, port -> {
      if (port.isPresent()) {
        return complete("The port was " + port.get());
      } else {
        return complete("The port was not provided explicitly");
      }
    });

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("example.com", 5043)))
                    .assertEntity("The port was 5043");

    testRoute(route).run(HttpRequest.GET("/"))
                    .assertEntity("The port was not provided explicitly");
    //#optionalHeaderValuePF
  }

  @Test
  public void testCheckSameOrigin() {
    //#checkSameOrigin
    final HttpOrigin validOriginHeader =
            HttpOrigin.create("http://localhost", Host.create("8080"));

    final HttpOriginRange validOriginRange = HttpOriginRange.create(validOriginHeader);

    final TestRoute route = testRoute(
            checkSameOrigin(validOriginRange,
                    () -> complete("Result")));

    route
            .run(HttpRequest.create().addHeader(Origin.create(validOriginHeader)))
            .assertStatusCode(StatusCodes.OK)
            .assertEntity("Result");

    route
            .run(HttpRequest.create())
            .assertStatusCode(StatusCodes.BAD_REQUEST);

    final HttpOrigin invalidOriginHeader =
            HttpOrigin.create("http://invalid.com", Host.create("8080"));

    route
            .run(HttpRequest.create().addHeader(Origin.create(invalidOriginHeader)))
            .assertStatusCode(StatusCodes.FORBIDDEN);
    //#checkSameOrigin
  }
}
