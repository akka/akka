/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//#stream-random-numbers
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

import java.util.Random;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class HttpServerStreamRandomNumbersTest extends AllDirectives {

  public static void main(String[] args) throws Exception {
    // boot up server using the route as defined below
    ActorSystem system = ActorSystem.create("routes");

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //In order to access all directives we need an instance where the routes are define.
    HttpServerStreamRandomNumbersTest app = new HttpServerStreamRandomNumbersTest();

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
    final Random rnd = new Random();
    // streams are re-usable so we can define it here
    // and use it for every request
    Source<Integer, NotUsed> numbers = Source.fromIterator(() -> Stream.generate(rnd::nextInt).iterator());

    return route(
        path("random", () ->
            get(() ->
                complete(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
                    // transform each number to a chunk of bytes
                    numbers.map(x -> ByteString.fromString(x + "\n")))))));
  }
}
//#stream-random-numbers