/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.dispatch.OnFailure;
import akka.http.impl.util.JavaMapping;
import akka.http.impl.util.Util;
import akka.http.javadsl.Http;
import akka.http.javadsl.IncomingConnection;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.Uri;
import akka.japi.JavaPartialFunction;
import akka.japi.function.Function;
import akka.japi.function.Procedure;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.Context;
import akka.stream.stage.PushStage;
import akka.stream.stage.SyncDirective;
import akka.stream.stage.TerminationDirective;
import akka.util.ByteString;
import scala.Function1;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class HttpServerExampleDocTest {

    public static void bindingExample() throws Exception {
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
        Await.result(serverBindingFuture, new FiniteDuration(3, TimeUnit.SECONDS));
    }

    public static void bindingFailureExample() throws Exception {
        //#binding-failure-handling
        ActorSystem system = ActorSystem.create();
        Materializer materializer = ActorMaterializer.create(system);

        Source<IncomingConnection, Future<ServerBinding>> serverSource =
            Http.get(system).bind("localhost", 80, materializer);

        Future<ServerBinding> serverBindingFuture =
            serverSource.to(Sink.foreach(
                new Procedure<IncomingConnection>() {
                    @Override
                    public void apply(IncomingConnection connection) throws Exception {
                        System.out.println("Accepted new connection from " + connection.remoteAddress());
                        // ... and then actually handle the connection
                    }
                })).run(materializer);

        serverBindingFuture.onFailure(new OnFailure() {
            @Override
            public void onFailure(Throwable failure) throws Throwable {
                // possibly report the failure somewhere...
            }
        }, system.dispatcher());
        //#binding-failure-handling
        Await.result(serverBindingFuture, new FiniteDuration(3, TimeUnit.SECONDS));
    }

    public static void connectionSourceFailureExample() throws Exception {
        //#incoming-connections-source-failure-handling
        ActorSystem system = ActorSystem.create();
        Materializer materializer = ActorMaterializer.create(system);

        Source<IncomingConnection, Future<ServerBinding>> serverSource =
            Http.get(system).bind("localhost", 8080, materializer);

        Flow<IncomingConnection, IncomingConnection, BoxedUnit> failureDetection =
            Flow.of(IncomingConnection.class).transform(() ->
                new PushStage<IncomingConnection, IncomingConnection>() {
                    @Override
                    public SyncDirective onPush(IncomingConnection elem, Context<IncomingConnection> ctx) {
                        return ctx.push(elem);
                    }

                    @Override
                    public TerminationDirective onUpstreamFailure(Throwable cause, Context<IncomingConnection> ctx) {
                        // signal the failure to external monitoring service!
                        return super.onUpstreamFailure(cause, ctx);
                    }
                });

        Future<ServerBinding> serverBindingFuture =
                serverSource
                        .via(failureDetection) // feed signals through our custom stage
                        .to(Sink.foreach(
                                new Procedure<IncomingConnection>() {
                                    @Override
                                    public void apply(IncomingConnection connection) throws Exception {
                                        System.out.println("Accepted new connection from " + connection.remoteAddress());
                                        // ... and then actually handle the connection
                                    }
                                })).run(materializer);
        //#incoming-connections-source-failure-handling
        Await.result(serverBindingFuture, new FiniteDuration(3, TimeUnit.SECONDS));
    }

    public static void connectionStreamFailureExample() throws Exception {
        //#connection-stream-failure-handling
        ActorSystem system = ActorSystem.create();
        Materializer materializer = ActorMaterializer.create(system);

        Source<IncomingConnection, Future<ServerBinding>> serverSource =
            Http.get(system).bind("localhost", 8080, materializer);

        Flow<HttpRequest, HttpRequest, BoxedUnit> failureDetection =
                Flow.of(HttpRequest.class).transform(() ->
                new PushStage<HttpRequest, HttpRequest>() {
                    @Override
                    public SyncDirective onPush(HttpRequest elem, Context<HttpRequest> ctx) {
                        return ctx.push(elem);
                    }

                    @Override
                    public TerminationDirective onUpstreamFailure(Throwable cause, Context<HttpRequest> ctx) {
                        // signal the failure to external monitoring service!
                        return super.onUpstreamFailure(cause, ctx);
                    }
                });

        Flow<HttpRequest, HttpResponse, BoxedUnit> httpEcho =
                Flow.of(HttpRequest.class)
                    .via(failureDetection)
                    .map(request -> {
                        Source<ByteString, ?> bytes = request.entity().getDataBytes();
                        HttpEntity entity = HttpEntities.create(ContentTypes.TEXT_PLAIN, (Source<ByteString, Object>) bytes);
                        return HttpResponse.create()
                                .withEntity(entity);
                    });

        Future<ServerBinding> serverBindingFuture =
            serverSource.to(Sink.foreach(con -> {
                        System.out.println("Accepted new connection from " + con.remoteAddress());
                        con.handleWith(httpEcho, materializer);
                    }
                )).run(materializer);
        //#connection-stream-failure-handling
        Await.result(serverBindingFuture, new FiniteDuration(3, TimeUnit.SECONDS));
    }

    public static void fullServerExample() throws Exception {
        //#full-server-example
            ActorSystem system = ActorSystem.create();
        //#full-server-example
        try {
            //#full-server-example
            final Materializer materializer = ActorMaterializer.create(system);

            Source<IncomingConnection, Future<ServerBinding>> serverSource =
                    Http.get(system).bind("localhost", 8080, materializer);

            //#request-handler
            final Function<HttpRequest, HttpResponse> requestHandler =
                new Function<HttpRequest, HttpResponse>() {
                    private final HttpResponse NOT_FOUND =
                        HttpResponse.create()
                            .withStatus(404)
                            .withEntity("Unknown resource!");


                    @Override
                    public HttpResponse apply(HttpRequest request) throws Exception {
                        Uri uri = request.getUri();
                        if (request.method() == HttpMethods.GET) {
                            if (uri.path().equals("/"))
                                return
                                    HttpResponse.create()
                                        .withEntity(ContentTypes.TEXT_HTML,
                                            "<html><body>Hello world!</body></html>");
                            else if (uri.path().equals("/hello")) {
                                String name = Util.getOrElse(uri.parameter("name"), "Mister X");

                                return
                                    HttpResponse.create()
                                        .withEntity("Hello " + name + "!");
                            }
                            else if (uri.path().equals("/ping"))
                                return HttpResponse.create().withEntity("PONG!");
                            else
                                return NOT_FOUND;
                        }
                        else return NOT_FOUND;
                    }
                };
            //#request-handler

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

            Await.result(serverBindingFuture, new FiniteDuration(1, TimeUnit.SECONDS)); // will throw if binding fails
            System.out.println("Press ENTER to stop.");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } finally {
            system.shutdown();
        }
    }
    public static void main(String[] args) throws Exception {
        fullServerExample();
    }
}
