/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

//#simple-app

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.StringUnmarshallers;
import akka.http.javadsl.server.examples.simple.SimpleServerApp;
import akka.stream.ActorMaterializer;

import java.io.IOException;

public class MyAppService extends AllDirectives {

  public String add(double x, double y) {
    return "x + y = " + (x + y);
  }

  public Route createRoute() {
    return
      get(() ->
        pathPrefix("calculator", () ->
          path("add", () ->
            parameter(StringUnmarshallers.DOUBLE, "x", x ->
              parameter(StringUnmarshallers.DOUBLE, "y", y ->
                complete(add(x, y))
              )
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
//#simple-app
