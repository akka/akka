/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.IncomingConnection;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class HttpServerExampleDocTest {

  public static void bindingExample() throws Exception {
    //#binding-example
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);

    Source<IncomingConnection, CompletionStage<ServerBinding>> serverSource =
      Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer);

    CompletionStage<ServerBinding> serverBindingFuture =
      serverSource.to(Sink.foreach(connection -> {
          System.out.println("Accepted new connection from " + connection.remoteAddress());
          // ... and then actually handle the connection
        }
      )).run(materializer);
    //#binding-example
    serverBindingFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  public static void bindingFailureExample() throws Exception {
    //#binding-failure-handling
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);

    Source<IncomingConnection, CompletionStage<ServerBinding>> serverSource =
      Http.get(system).bind(ConnectHttp.toHost("localhost", 80), materializer);

    CompletionStage<ServerBinding> serverBindingFuture =
      serverSource.to(Sink.foreach(connection -> {
          System.out.println("Accepted new connection from " + connection.remoteAddress());
          // ... and then actually handle the connection
        }
      )).run(materializer);

    serverBindingFuture.whenCompleteAsync((binding, failure) -> {
      // possibly report the failure somewhere...
    }, system.dispatcher());
    //#binding-failure-handling
    serverBindingFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  public static void connectionSourceFailureExample() throws Exception {
    //#incoming-connections-source-failure-handling
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);

    Source<IncomingConnection, CompletionStage<ServerBinding>> serverSource =
      Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer);

    Flow<IncomingConnection, IncomingConnection, NotUsed> failureDetection =
      Flow.of(IncomingConnection.class).watchTermination((notUsed, termination) -> {
        termination.whenComplete((done, cause) -> {
          if (cause != null) {
            // signal the failure to external monitoring service!
          }
        });
        return NotUsed.getInstance();
      });

    CompletionStage<ServerBinding> serverBindingFuture =
      serverSource
        .via(failureDetection) // feed signals through our custom stage
        .to(Sink.foreach(connection -> {
          System.out.println("Accepted new connection from " + connection.remoteAddress());
          // ... and then actually handle the connection
        }))
        .run(materializer);
    //#incoming-connections-source-failure-handling
    serverBindingFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  public static void connectionStreamFailureExample() throws Exception {
    //#connection-stream-failure-handling
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);

    Source<IncomingConnection, CompletionStage<ServerBinding>> serverSource =
      Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer);

    Flow<HttpRequest, HttpRequest, NotUsed> failureDetection =
      Flow.of(HttpRequest.class)
        .watchTermination((notUsed, termination) -> {
          termination.whenComplete((done, cause) -> {
            if (cause != null) {
              // signal the failure to external monitoring service!
            }
          });
          return NotUsed.getInstance();
        });

    Flow<HttpRequest, HttpResponse, NotUsed> httpEcho =
      Flow.of(HttpRequest.class)
        .via(failureDetection)
        .map(request -> {
          Source<ByteString, Object> bytes = request.entity().getDataBytes();
          HttpEntity.Chunked entity = HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, bytes);

          return HttpResponse.create()
            .withEntity(entity);
        });

    CompletionStage<ServerBinding> serverBindingFuture =
      serverSource.to(Sink.foreach(conn -> {
          System.out.println("Accepted new connection from " + conn.remoteAddress());
          conn.handleWith(httpEcho, materializer);
        }
      )).run(materializer);
    //#connection-stream-failure-handling
    serverBindingFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  public static void fullServerExample() throws Exception {
    //#full-server-example
    ActorSystem system = ActorSystem.create();
    //#full-server-example
    try {
      //#full-server-example
      final Materializer materializer = ActorMaterializer.create(system);

      Source<IncomingConnection, CompletionStage<ServerBinding>> serverSource =
        Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer);

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
              if (uri.path().equals("/")) {
                return
                  HttpResponse.create()
                    .withEntity(ContentTypes.TEXT_HTML_UTF8,
                      "<html><body>Hello world!</body></html>");
              } else if (uri.path().equals("/hello")) {
                String name = uri.query().get("name").orElse("Mister X");

                return
                  HttpResponse.create()
                    .withEntity("Hello " + name + "!");
              } else if (uri.path().equals("/ping")) {
                return HttpResponse.create().withEntity("PONG!");
              } else {
                return NOT_FOUND;
              }
            } else {
              return NOT_FOUND;
            }
          }
        };
      //#request-handler

      CompletionStage<ServerBinding> serverBindingFuture =
        serverSource.to(Sink.foreach(connection -> {
          System.out.println("Accepted new connection from " + connection.remoteAddress());

          connection.handleWithSyncHandler(requestHandler, materializer);
          // this is equivalent to
          //connection.handleWith(Flow.of(HttpRequest.class).map(requestHandler), materializer);
        })).run(materializer);
      //#full-server-example

      serverBindingFuture.toCompletableFuture().get(1, TimeUnit.SECONDS); // will throw if binding fails
      System.out.println("Press ENTER to stop.");
      new BufferedReader(new InputStreamReader(System.in)).readLine();
    } finally {
      system.terminate();
    }
  }

  public static void main(String[] args) throws Exception {
    fullServerExample();
  }
}
