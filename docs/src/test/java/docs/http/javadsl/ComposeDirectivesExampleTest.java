/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.PathMatcher1;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import static akka.http.javadsl.common.PartialApplication.*;
import static akka.http.javadsl.server.PathMatchers.*;
import static akka.http.javadsl.server.Directives.*;


import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ComposeDirectivesExampleTest extends AllDirectives {

  public static void main(String[] args) throws Exception {
    // boot up server using the route as defined below
    ActorSystem system = ActorSystem.create("routes");

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //In order to access all directives we need an instance where the routes are define.
    ComposeDirectivesExampleTest app = new ComposeDirectivesExampleTest();

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow,
        ConnectHttp.toHost("localhost", 8080), materializer);

    System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
    System.in.read(); // let it run until user presses return

    binding
        .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
        .thenAccept(unbound -> system.terminate()); // and shutdown when done
  }

  private Route createRoute() {
    BiFunction<PathMatcher1<Integer>, Function<Integer,Route>, Route> pathWithInteger = this::path;

    return route(
      //anyOf examples
      path("hello", () ->
        anyOf(this::get, this::put, () ->
          complete("<h1>Say hello to akka-http</h1>"))),
      path("foo", () ->
        anyOf(bindParameter(this::parameter, "foo"), bindParameter(this::parameter, "bar"), (String param) ->
          complete("param is " + param))
      ),
      anyOf(bindParameter(this::path, "bar"), bindParameter(this::path, "baz"), () ->
        complete("bar - baz")),

      //allOf examples
      allOf(bindParameter(this::pathPrefix, "alice"), bindParameter(this::path, "bob"), () ->
        complete("Charlie!")),
      allOf(bindParameter(this::pathPrefix, "guess"), this::extractMethod, method ->
        complete("You did a " + method.name())),
      path("two", () ->
        allOf(this::extractScheme, this::extractMethod, (scheme, method) ->
          complete("You did a " + method.name() + " using " + scheme))
      ),
      allOf(bindParameter(this::pathPrefix, "number"), bindParameter(pathWithInteger, integerSegment()), x ->
        complete("Number is " + x))
    );
  }
}
