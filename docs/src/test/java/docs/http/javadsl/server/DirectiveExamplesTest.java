/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.http.javadsl.model.RemoteAddress;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.function.Function;
import java.util.function.Supplier;

import static akka.http.javadsl.server.PathMatchers.integerSegment;
import static akka.http.javadsl.server.PathMatchers.segment;
import static akka.http.javadsl.server.Directives.*;

public class DirectiveExamplesTest extends JUnitRouteTest {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  //#example1
  Route orElse() {
    return path(segment("order").slash(integerSegment()), id ->
      get(() -> complete("Received GET request for order " + id))
        .orElse(
          put(() -> complete("Recieved PUT request for order " + id)))
    );
  }
  //#example1

  //#usingRoute
  Route usingRoute() {
    return path(segment("order").slash(integerSegment()), id ->
      route(get(() -> complete("Received GET request for order " + id)),
            put(() -> complete("Received PUT request for order " + id)))
    );
  }
  //#usingRoute

  //#usingRouteBig
  Route multipleRoutes() {
    return path(segment("order").slash(integerSegment()), id ->
      route(get(()  -> complete("Received GET request for order " + id)),
            put(()  -> complete("Received PUT request for order " + id)),
            head(() -> complete("Received HEAD request for order " + id)))
    );
  }
  //#usingRouteBig

  //#getOrPut
  Route getOrPut(Supplier<Route> inner) {
    return get(inner)
      .orElse(put(inner));
  }

  Route customDirective() {
    return path(segment("order").slash(integerSegment()), id ->
      getOrPut(() ->
        extractMethod(method -> complete("Received " + method + " for order " + id)))
    );
  }
  //#getOrPut

  //#getOrPutUsingAnyOf
  Route usingAnyOf() {
    return path(segment("order").slash(integerSegment()), id ->
      anyOf(this::get, this::put, () ->
        extractMethod(method -> complete("Received " + method + " for order " + id)))
    );
  }
  //#getOrPutUsingAnyOf

  //#composeNesting
  Route getWithIP(Function<RemoteAddress, Route> inner) {
    return get(() ->
        extractClientIP(address -> inner.apply(address))
    );
  }

  Route complexRoute() {
    return path(segment("order").slash(integerSegment()), id ->
      getWithIP(address ->
        complete("Received request for order " + id + " from IP " + address))
    );
  }
  //#composeNesting

  //#composeNestingAllOf
  Route complexRouteUsingAllOf() {
    return path(segment("order").slash(integerSegment()), id ->
      allOf(this::get, this::extractClientIP, address ->
        complete("Received request for order " + id + " from IP " + address))
    );
  }
  //#composeNestingAllOf
}
