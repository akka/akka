/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//#single-request-example
import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

import java.util.concurrent.CompletionStage;

public class ClientSingleRequestExample {

  public static void main(String[] args) {
    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = ActorMaterializer.create(system);

    final CompletionStage<HttpResponse> responseFuture =
      Http.get(system)
        .singleRequest(HttpRequest.create("http://akka.io"), materializer);
  }
}
//#single-request-example

