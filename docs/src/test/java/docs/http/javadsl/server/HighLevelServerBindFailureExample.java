/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#binding-failure-high-level-example

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.Http;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

public class HighLevelServerBindFailureExample {
  public static void main(String[] args) throws IOException {
    // boot up server using the route as defined below
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    // HttpApp.bindRoute expects a route being provided by HttpApp.createRoute
    final HighLevelServerExample app = new HighLevelServerExample();
    final Route route = app.createRoute();

    final Flow<HttpRequest, HttpResponse, NotUsed> handler = route.flow(system, materializer);
    final CompletionStage<ServerBinding> binding = Http.get(system).bindAndHandle(handler, ConnectHttp.toHost("127.0.0.1", 8080), materializer);

    binding.exceptionally(failure -> {
      System.err.println("Something very bad happened! " + failure.getMessage());
      system.terminate();
      return null;
    });

    system.terminate();
  }
}
//#binding-failure-high-level-example
