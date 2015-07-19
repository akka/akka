/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.HostConnectionPool;
import akka.japi.Option;
import akka.japi.Pair;
import akka.util.ByteString;
import org.junit.Test;

import scala.Tuple2;
import scala.concurrent.Future;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.*;
import akka.http.javadsl.OutgoingConnection;
import akka.http.javadsl.model.*;
import akka.http.javadsl.Http;
import scala.util.Try;

public class HttpClientExampleDocTest {

    // compile only test
    public void testConstructRequest() {
        //#outgoing-connection-example

        final ActorSystem system = ActorSystem.create();
        final ActorMaterializer materializer = ActorMaterializer.create(system);

        final Flow<HttpRequest, HttpResponse, Future<OutgoingConnection>> connectionFlow =
                Http.get(system).outgoingConnection("akka.io", 80);
        final Future<HttpResponse> responseFuture =
                Source.single(HttpRequest.create("/"))
                        .via(connectionFlow)
                        .runWith(Sink.head(), materializer);
        //#outgoing-connection-example
    }

  // compile only test
  public void testHostLevelExample() {
    //#host-level-example
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    // construct a pool client flow with context type `Int`
    // TODO these Tuple2 will be changed to akka.japi.Pair
    final Flow<
      Tuple2<HttpRequest, Integer>,
      Tuple2<Try<HttpResponse>, Integer>,
      HostConnectionPool> poolClientFlow =
      Http.get(system).<Integer>cachedHostConnectionPool("akka.io", 80, materializer);

    // construct a pool client flow with context type `Int`

    final Future<Tuple2<Try<HttpResponse>, Integer>> responseFuture =
      Source
        .single(Pair.create(HttpRequest.create("/"), 42).toScala())
        .via(poolClientFlow)
        .runWith(Sink.head(), materializer);
    //#host-level-example
  }

  // compile only test
  public void testSingleRequestExample() {
    //#single-request-example
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Future<HttpResponse> responseFuture =
      Http.get(system)
          .singleRequest(HttpRequest.create("http://akka.io"), materializer);
    //#single-request-example
  }
}