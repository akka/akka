/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.Http;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import static akka.http.javadsl.server.PathMatchers.integerSegment;

//#explicit-handler-example
public class ExceptionHandlerExample extends AllDirectives {
  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);
    final Http http = Http.get(system);

    final ExceptionHandlerExample app = new ExceptionHandlerExample();

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);
  }


  public Route createRoute() {
    final ExceptionHandler divByZeroHandler = ExceptionHandler.newBuilder()
      .match(ArithmeticException.class, x ->
        complete(StatusCodes.BAD_REQUEST, "You've got your arithmetic wrong, fool!"))
      .build();

    return path(PathMatchers.segment("divide").slash(integerSegment()).slash(integerSegment()), (a, b) ->
      handleExceptions(divByZeroHandler, () -> complete("The result is " + (a / b)))
    );
  }
}
//#explicit-handler-example
