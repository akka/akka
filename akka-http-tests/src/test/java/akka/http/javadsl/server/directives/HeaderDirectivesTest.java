/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.japi.pf.PFBuilder;
import org.junit.Test;

import java.util.Optional;

public class HeaderDirectivesTest extends JUnitRouteTest {

  @Test
  public void testHeaderValue() {
    TestRoute route = testRoute(headerValue((header) -> {
      if (header.name().equals("X-Test-Header")) {
        if (header.value().equals("bad value")) throw new RuntimeException("bad value");
        else return Optional.of(header.value());
      }
      else return Optional.empty();
    },
    this::complete));

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Test-Header", "woho!")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("woho!");

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Test-Header", "bad value")))
      .assertStatusCode(StatusCodes.BAD_REQUEST);


    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testHeaderValuePF() {
    TestRoute route = testRoute(headerValuePF(
      new PFBuilder<HttpHeader, String>().<Host>match(
        Host.class, Host::value
      ).build(),
      this::complete));

    route
      .run(HttpRequest.create().addHeader(Host.create("example.com")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("example.com");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testHeaderValueByName() {
    TestRoute route = testRoute(headerValueByName("X-Test-Header", this::complete));

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Test-Header", "woho!")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("woho!");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.BAD_REQUEST);
  }

  @Test
  public void testHeaderValueByType() {
    TestRoute route = testRoute(headerValueByType(Server.class,
      (Server s) -> complete(s.getProducts().iterator().next().product())));

    route
      .run(HttpRequest.create().addHeader(Server.create(ProductVersion.create("such-service", "0.6"))))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("such-service");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.BAD_REQUEST);
  }

  @Test
  public void testOptionalHeaderValue() {
    TestRoute route = testRoute(optionalHeaderValue((header) -> {
        if (header.name().equals("X-Test-Header")) {
          if (header.value().equals("bad value")) throw new RuntimeException("bad value");
          else return Optional.of(header.value());
        }
        else return Optional.empty();
      },
      opt -> complete(opt.toString())));

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Test-Header", "woho!")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional[woho!]");

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Test-Header", "bad value")))
      .assertStatusCode(StatusCodes.BAD_REQUEST);


    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional.empty");
  }

  @Test
  public void testOptionalHeaderValuePF() {
    TestRoute route = testRoute(optionalHeaderValuePF(
      new PFBuilder<HttpHeader, String>().<Host>match(
        Host.class, Host::value
      ).build(),
      (opt) -> complete(opt.toString())));

    route
      .run(HttpRequest.create().addHeader(Host.create("example.com")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional[example.com]");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional.empty");
  }


  @Test
  public void testOptionalHeaderValueByName() {
    TestRoute route = testRoute(optionalHeaderValueByName("X-Test-Header", (opt) -> complete(opt.toString())));

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Test-Header", "woho!")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional[woho!]");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional.empty");
  }

  @Test
  public void testOptionalHeaderValueByType() {
    TestRoute route = testRoute(optionalHeaderValueByType(Server.class,
      (Optional<Server> s) -> complete(((Boolean)s.isPresent()).toString())));

    route
      .run(HttpRequest.create().addHeader(Server.create(ProductVersion.create("such-service", "0.6"))))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("true");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("false");
  }

  @Test
  public void testValueByTypeHandlesCustomHeaders() {
    TestRoute route = testRoute(headerValueByType(SampleCustomHeader.class,
      (SampleCustomHeader m) -> complete(m.value())));

    route
      .run(HttpRequest.create().addHeader(RawHeader.create("X-Sample-Custom-Header", "such header")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("X-Sample-Custom-Header: such header");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.BAD_REQUEST);
  }

  @Test
  public void testCheckSameOrigin() {
    final HttpOrigin validOriginHeader = HttpOrigin.create("http://localhost", Host.create("8080"));

    final HttpOriginRange validOriginRange = HttpOriginRange.create(validOriginHeader);

    TestRoute route = testRoute(checkSameOrigin(validOriginRange, () -> complete("Result")));

    route
      .run(HttpRequest.create().addHeader(Origin.create(validOriginHeader)))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Result");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.BAD_REQUEST);

    final HttpOrigin invalidOriginHeader = HttpOrigin.create("http://invalid.com", Host.create("8080"));

    route
      .run(HttpRequest.create().addHeader(Origin.create(invalidOriginHeader)))
      .assertStatusCode(StatusCodes.FORBIDDEN);
  }

}
