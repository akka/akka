/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.io.IOException;

import java.util.concurrent.CompletionStage;

//#route-seal-example
public class RouteSealExample extends AllDirectives {

  public static void main(String [] args) throws IOException {
    RouteSealExample app = new RouteSealExample();
    app.runServer();
  }

  public void runServer(){
    ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Route sealedRoute = get(
      () -> pathSingleSlash( () ->
        complete("Captain on the bridge!")
      )
    ).seal(system, materializer);

    Route route = respondWithHeader(
      RawHeader.create("special-header", "you always have this even in 404"),
      () -> sealedRoute
    );

    final Http http = Http.get(system);
    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = route.flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);
  }
}
//#route-seal-example