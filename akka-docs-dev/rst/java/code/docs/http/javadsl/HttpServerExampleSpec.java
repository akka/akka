/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.IncomingConnection;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.ws.Websocket;
import akka.japi.function.Function;
import akka.japi.function.Procedure;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.junit.Test;
import scala.Function1;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class HttpServerExampleSpec {
    public void bindingExample() {
        //#binding-example
        ActorSystem system = ActorSystem.create();
        Materializer materializer = ActorMaterializer.create(system);

        Source<IncomingConnection, Future<ServerBinding>> serverSource =
            Http.get(system).bind("localhost", 8080, materializer);

        Future<ServerBinding> serverBindingFuture =
            serverSource.to(Sink.foreach(
                new Procedure<IncomingConnection>() {
                    @Override
                    public void apply(IncomingConnection connection) throws Exception {
                        System.out.println("Accepted new connection from " + connection.remoteAddress());
                        // ... and then actually handle the connection
                    }
                })).run(materializer);
        //#binding-example
    }
    public void fullServerExample() {
        //#full-server-example
        ActorSystem system = ActorSystem.create();
        final Materializer materializer = ActorMaterializer.create(system);

        Source<IncomingConnection, Future<ServerBinding>> serverSource =
                Http.get(system).bind("localhost", 8080, materializer);

        final Function<HttpRequest, HttpResponse> requestHandler =
            new Function<HttpRequest, HttpResponse>() {
                private final HttpResponse NOT_FOUND =
                    HttpResponse.create()
                        .withStatus(404)
                        .withEntity("Unknown resource!");

                @Override
                public HttpResponse apply(HttpRequest request) throws Exception {
                    if (request.method() == HttpMethods.GET) {
                        if (request.getUri().path().equals("/"))
                            return
                                HttpResponse.create()
                                    .withEntity(MediaTypes.TEXT_HTML.toContentType(),
                                        "<html><body>Hello world!</body></html>");
                        else if (request.getUri().path().equals("/ping"))
                            return HttpResponse.create().withEntity("PONG!");
                        else
                            return NOT_FOUND;
                    }
                    else return NOT_FOUND;
                }
            };

        Future<ServerBinding> serverBindingFuture =
                serverSource.to(Sink.foreach(
                        new Procedure<IncomingConnection>() {
                            @Override
                            public void apply(IncomingConnection connection) throws Exception {
                                System.out.println("Accepted new connection from " + connection.remoteAddress());

                                connection.handleWithSyncHandler(requestHandler, materializer);
                                // this is equivalent to
                                //connection.handleWith(Flow.of(HttpRequest.class).map(requestHandler), materializer);
                            }
                        })).run(materializer);
        //#full-server-example
    }
}
