/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.io.IOException;

public class SimpleServerHttpHttpsApp extends AllDirectives { // or import Directives.*

  public Route createRoute() {
    return get( () -> complete("Hello World!") );
  }

  // ** STARTING THE SERVER ** //

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create("SimpleServerHttpHttpsApp");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final SimpleServerApp app = new SimpleServerApp();
    final Flow<HttpRequest, HttpResponse, NotUsed> flow = app.createRoute().flow(system, materializer);

    //#both-https-and-http
    final Http http = Http.get(system);
    //Run HTTP server firstly
    http.bindAndHandle(flow, ConnectHttp.toHost("localhost", 80), materializer);

    //get configured HTTPS context
    HttpsConnectionContext https = SimpleServerApp.useHttps(system);

    // sets default context to HTTPS – all Http() bound servers for this ActorSystem will use HTTPS from now on
    http.setDefaultServerHttpContext(https);

    //Then run HTTPS server
    http.bindAndHandle(flow, ConnectHttp.toHost("localhost", 443), materializer);
    //#

    System.out.println("Type RETURN to exit");
    System.in.read();
    system.terminate();
  }
}
