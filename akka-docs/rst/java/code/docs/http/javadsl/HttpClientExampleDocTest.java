/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.HostConnectionPool;
import akka.japi.Pair;

import akka.japi.pf.ReceiveBuilder;
import akka.stream.Materializer;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.*;
import akka.http.javadsl.OutgoingConnection;
import akka.http.javadsl.model.*;
import akka.http.javadsl.Http;
import scala.util.Try;

import static akka.http.javadsl.ConnectHttp.toHost;
import static akka.pattern.PatternsCS.*;

import java.util.concurrent.CompletionStage;

@SuppressWarnings("unused")
public class HttpClientExampleDocTest {

    // compile only test
    public void testConstructRequest() {
        //#outgoing-connection-example

        final ActorSystem system = ActorSystem.create();
        final ActorMaterializer materializer = ActorMaterializer.create(system);

        final Flow<HttpRequest, HttpResponse, CompletionStage<OutgoingConnection>> connectionFlow =
                Http.get(system).outgoingConnection(toHost("akka.io", 80));
        final CompletionStage<HttpResponse> responseFuture =
                Source.single(HttpRequest.create("/"))
                        .via(connectionFlow)
                        .runWith(Sink.<HttpResponse>head(), materializer);
        //#outgoing-connection-example
    }

  // compile only test
  public void testHostLevelExample() {
    //#host-level-example
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    // construct a pool client flow with context type `Integer`
    final Flow<
      Pair<HttpRequest, Integer>,
      Pair<Try<HttpResponse>, Integer>,
      HostConnectionPool> poolClientFlow =
      Http.get(system).<Integer>cachedHostConnectionPool(toHost("akka.io", 80), materializer);

    // construct a pool client flow with context type `Integer`

    final CompletionStage<Pair<Try<HttpResponse>, Integer>> responseFuture =
      Source
        .single(Pair.create(HttpRequest.create("/"), 42))
        .via(poolClientFlow)
        .runWith(Sink.<Pair<Try<HttpResponse>, Integer>>head(), materializer);
    //#host-level-example
  }

  // compile only test
  public void testSingleRequestExample() {
    //#single-request-example
    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = ActorMaterializer.create(system);

    final CompletionStage<HttpResponse> responseFuture =
      Http.get(system)
          .singleRequest(HttpRequest.create("http://akka.io"), materializer);
    //#single-request-example
  }

  static
      //#single-request-in-actor-example
    class Myself extends AbstractActor {
      final Http http = Http.get(context().system());
      final ExecutionContextExecutor dispatcher = context().dispatcher();
      final Materializer materializer = ActorMaterializer.create(context());

      public Myself() {
        receive(ReceiveBuilder
         .match(String.class, url -> {
           pipe(fetch (url), dispatcher).to(self());
         }).build());
      }

      CompletionStage<HttpResponse> fetch(String url) {
        return http.singleRequest(HttpRequest.create(url), materializer);
      }
    }
    //#single-request-in-actor-example

}
