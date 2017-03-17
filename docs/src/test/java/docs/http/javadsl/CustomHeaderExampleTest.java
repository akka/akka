/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.ModeledCustomHeader;
import akka.http.javadsl.model.headers.ModeledCustomHeaderFactory;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.japi.JavaPartialFunction;
import org.junit.Test;
import scala.PartialFunction;

import java.util.Optional;

import static org.junit.Assert.*;

public class CustomHeaderExampleTest extends JUnitRouteTest {
  //#modeled-api-key-custom-header
  public static class ApiTokenHeader extends ModeledCustomHeader {

    ApiTokenHeader(String name, String value) {
      super(name, value);
    }

    public boolean renderInResponses() {
      return false;
    }

    public boolean renderInRequests() {
      return false;
    }

  }

  static class ApiTokenHeaderFactory extends ModeledCustomHeaderFactory<ApiTokenHeader> {

    public String name() {
      return "apiKey";
    }

    @Override
    public ApiTokenHeader parse(String value) {
      return new ApiTokenHeader(name(), value);
    }

  }
  //#modeled-api-key-custom-header

  @Test
  public void showCreation() {
    //#conversion-creation-custom-header
    final ApiTokenHeaderFactory apiTokenHeaderFactory = new ApiTokenHeaderFactory();
    final ApiTokenHeader token = apiTokenHeaderFactory.create("token");
    assertEquals("token", token.value());

    final HttpHeader header = apiTokenHeaderFactory.create("token");
    assertEquals("apiKey", header.name());
    assertEquals("token", header.value());

    final Optional<ApiTokenHeader> fromRaw = apiTokenHeaderFactory
      .from(RawHeader.create("apiKey", "token"));
    assertTrue("Expected a header", fromRaw.isPresent());
    assertEquals("apiKey", fromRaw.get().name());
    assertEquals("token", fromRaw.get().value());

    // will match, header keys are case insensitive
    final Optional<ApiTokenHeader> fromRawUpper = apiTokenHeaderFactory
      .from(RawHeader.create("APIKEY", "token"));
    assertTrue("Expected a header", fromRawUpper.isPresent());
    assertEquals("apiKey", fromRawUpper.get().name());
    assertEquals("token", fromRawUpper.get().value());

    // won't match, different header name
    final Optional<ApiTokenHeader> wrong = apiTokenHeaderFactory
      .from(RawHeader.create("different", "token"));
    assertFalse(wrong.isPresent());
    //#conversion-creation-custom-header
  }

  @Test
  public void testExtraction() {
    //#header-value-pf
    final ApiTokenHeaderFactory apiTokenHeaderFactory = new ApiTokenHeaderFactory();
    final PartialFunction<HttpHeader, String> extractFromCustomHeader =
      new JavaPartialFunction<HttpHeader, String>() {

        @Override
        public String apply(HttpHeader header, boolean isCheck) throws Exception {
          if (isCheck)
            return null;
          return apiTokenHeaderFactory.from(header)
            .map(apiTokenHeader -> "extracted> " + apiTokenHeader)
            .orElseGet(() -> "raw> " + header);
        }
      };

    final Route route = headerValuePF(extractFromCustomHeader, this::complete);

    testRoute(route)
      .run(HttpRequest.GET("/").addHeader(RawHeader.create("apiKey", "TheKey")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("extracted> apiKey: TheKey");

    testRoute(route)
      .run(HttpRequest.GET("/").addHeader(RawHeader.create("somethingElse", "TheKey")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("raw> somethingElse: TheKey");

    testRoute(route)
      .run(HttpRequest.GET("/").addHeader(apiTokenHeaderFactory.create("TheKey")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("extracted> apiKey: TheKey");
    //#header-value-pf
  }

}
