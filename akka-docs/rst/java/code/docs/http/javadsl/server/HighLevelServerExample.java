/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#high-level-server-example

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

public class HighLevelServerExample extends AllDirectives {
  public static void main(String[] args) throws IOException {
    // boot up server using the route as defined below
    ActorSystem system = ActorSystem.create();

    // HttpApp.bindRoute expects a route being provided by HttpApp.createRoute
    final HighLevelServerExample app = new HighLevelServerExample();

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);

    System.out.println("Type RETURN to exit");
    System.in.read();
    
    binding
      .thenCompose(ServerBinding::unbind)
      .thenAccept(unbound -> system.terminate());
  }

  public Route createRoute() {
    // This handler generates responses to `/hello?name=XXX` requests
    Route helloRoute =
      parameterOptional("name", optName -> {
        String name = optName.orElse("Mister X");
        return complete("Hello " + name + "!");
      });

    return
      // here the complete behavior for this server is defined

      // only handle GET requests
      get(() -> route(
        // matches the empty path
        pathSingleSlash(() ->
          // return a constant string with a certain content type
          complete(HttpEntities.create(ContentTypes.TEXT_HTML_UTF8, "<html><body>Hello world!</body></html>"))
        ),
        path("ping", () ->
          // return a simple `text/plain` response
          complete("PONG!")
        ),
        path("hello", () ->
          // uses the route defined above
          helloRoute
        )
      ));
  }
}
//#high-level-server-example
