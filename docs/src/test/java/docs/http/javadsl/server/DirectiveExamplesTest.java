/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.http.javadsl.model.HttpMethod;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.function.Function;
import java.util.function.Supplier;

import static akka.http.javadsl.server.PathMatchers.integerSegment;
import static akka.http.javadsl.server.PathMatchers.segment;

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
  //#composeNesting
  Route getOrPut(Supplier<Route> inner) {
    return get(inner)
      .orElse(put(inner));
  }
  //#composeNesting

  Route customDirective() {
    return path(segment("order").slash(integerSegment()), id ->
      getOrPut(() ->
        extractMethod(method -> complete("Received " + method + " for order " + id)))
    );
  }
  //#getOrPut

  //#composeNesting

  Route getOrPutWithMethod(Function<HttpMethod, Route> inner) {
    return getOrPut(() ->
        extractMethod(method -> inner.apply(method))
    );
  }

  Route complexRoute() {
    return path(segment("order").slash(integerSegment()), id ->
      getOrPutWithMethod(method -> complete("Received " + method + " for order " + id))
    );
  }
  //#composeNesting
}
