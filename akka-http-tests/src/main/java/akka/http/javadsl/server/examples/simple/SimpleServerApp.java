/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

import static akka.http.javadsl.server.PathMatchers.INTEGER_SEGMENT;
import static akka.http.javadsl.server.PathMatcher.segment;
import static akka.http.javadsl.server.StringUnmarshallers.INTEGER;
import static akka.http.javadsl.server.Unmarshaller.entityToString;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;

public class SimpleServerApp extends AllDirectives { // or import Directives.*

  public Route multiply(int x, int y) {
    int result = x * y;
    return complete(String.format("%d * %d = %d", x, y, result));
  }

  public CompletionStage<Route> multiplyAsync(Executor ctx, int x, int y) {
    return CompletableFuture.supplyAsync(() -> multiply(x, y), ctx);
  }

  public Route createRoute() {
    Route addHandler = parameter(INTEGER, "x", x ->
      parameter(INTEGER, "y", y -> {
        int result = x + y;
        return complete(String.format("%d + %d = %d", x, y, result));
      })
    );

    BiFunction<Integer, Integer, Route> subtractHandler = (x, y) -> {
      int result = x - y;
      return complete(String.format("%d - %d = %d", x, y, result));
    };

    return
      route(
        // matches the empty path
        pathSingleSlash(() ->
          getFromResource("web/calculator.html")
        ),
        // matches paths like this: /add?x=42&y=23
        path("add", () -> addHandler),
        path("subtract", () ->
          parameter(INTEGER, "x", x ->
            parameter(INTEGER, "y", y ->
              subtractHandler.apply(x, y)
            )
          )
        ),
        // matches paths like this: /multiply/{x}/{y}
        path(segment("multiply").slash(INTEGER_SEGMENT).slash(INTEGER_SEGMENT),
          this::multiply
        ),
        path(segment("multiplyAsync").slash(INTEGER_SEGMENT).slash(INTEGER_SEGMENT), (x, y) ->
          extractExecutionContext(ctx ->
            onSuccess(() -> multiplyAsync(ctx, x, y), Function.identity())
          )
        ),
        post(() ->
          path("hello", () ->
            entity(entityToString(), body ->
              complete("Hello " + body + "!")
            )
          )
        )
      );
  }

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final SimpleServerApp app = new SimpleServerApp();

    final ConnectHttp host = ConnectHttp.toHost("127.0.0.1");

    Http.get(system).bindAndHandle(app.createRoute().flow(system, materializer), host, materializer);

    System.console().readLine("Type RETURN to exit...");
    system.terminate();
  }
}