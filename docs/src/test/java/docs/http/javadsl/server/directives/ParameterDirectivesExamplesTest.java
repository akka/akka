/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import org.junit.Test;

import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ParameterDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testParameter() {
    //#parameter
    final Route route = parameter("color", color ->
      complete("The color is '" + color + "'")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue"))
      .assertEntity("The color is 'blue'");

    testRoute(route).run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertEntity("Request is missing required query parameter 'color'");
    //#parameter
  }

  @Test
  public void testParameters() {
    //#parameters
    final Route route = parameter("color", color ->
      parameter("backgroundColor", backgroundColor ->
        complete("The color is '" + color
                   + "' and the background is '" + backgroundColor + "'")
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue&backgroundColor=red"))
      .assertEntity("The color is 'blue' and the background is 'red'");

    testRoute(route).run(HttpRequest.GET("/?color=blue"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertEntity("Request is missing required query parameter 'backgroundColor'");
    //#parameters
  }

  @Test
  public void testParameterOptional() {
    //#optional
    final Route route = parameter("color", color ->
            parameterOptional("backgroundColor", backgroundColor ->
                    complete("The color is '" + color
                            + "' and the background is '" + backgroundColor.orElse("undefined") + "'")
            )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue&backgroundColor=red"))
            .assertEntity("The color is 'blue' and the background is 'red'");

    testRoute(route).run(HttpRequest.GET("/?color=blue"))
            .assertEntity("The color is 'blue' and the background is 'undefined'");
    //#optional
  }

  @Test
  public void testParameterMappedValue() {
    //#mapped-value
    final Route route = parameter("color", color ->
            parameter(StringUnmarshallers.INTEGER,"count", count ->
                    complete("The color is '" + color + "' and you have " + count + " of it.")
            )
    );
    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue&count=42"))
            .assertEntity("The color is 'blue' and you have 42 of it.");

    testRoute(route).run(HttpRequest.GET("/?color=blue&count=blub"))
            .assertStatusCode(StatusCodes.BAD_REQUEST)
            .assertEntity("The query parameter 'count' was malformed:\n'blub' is not a valid 32-bit signed integer value");
    //#mapped-value
  }

  @Test
  public void testParameterMap() {
    //#parameterMap
    final Function<Entry, String> paramString =
      entry -> entry.getKey() + " = '" + entry.getValue() + "'";

    final Route route = parameterMap(params -> {
      final String pString = params.entrySet()
        .stream()
        .map(paramString::apply)
        .collect(Collectors.joining(", "));
      return complete("The parameters are " + pString);
    });

    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue&count=42"))
      .assertEntity("The parameters are color = 'blue', count = '42'");

    testRoute(route).run(HttpRequest.GET("/?x=1&x=2"))
      .assertEntity("The parameters are x = '2'");
    //#parameterMap
  }

  @Test
  public void testParameterMultiMap() {
    //#parameterMultiMap
    final Route route = parameterMultiMap(params -> {
      final String pString = params.entrySet()
        .stream()
        .map(e -> e.getKey() + " -> " + e.getValue().size())
        .collect(Collectors.joining(", "));
      return complete("There are parameters " + pString);
    });

    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue&count=42"))
      .assertEntity("There are parameters color -> 1, count -> 1");

    testRoute(route).run(HttpRequest.GET("/?x=23&x=42"))
      .assertEntity("There are parameters x -> 2");
    //#parameterMultiMap
  }

  @Test
  public void testParameterSeq() {
    //#parameterSeq
    final Function<Entry, String> paramString =
      entry -> entry.getKey() + " = '" + entry.getValue() + "'";

    final Route route = parameterList(params -> {
      final String pString = params.stream()
        .map(paramString::apply)
        .collect(Collectors.joining(", "));

      return complete("The parameters are " + pString);
    });

    // tests:
    testRoute(route).run(HttpRequest.GET("/?color=blue&count=42"))
      .assertEntity("The parameters are color = 'blue', count = '42'");

    testRoute(route).run(HttpRequest.GET("/?x=1&x=2"))
      .assertEntity("The parameters are x = '1', x = '2'");
    //#parameterSeq
  }

}
