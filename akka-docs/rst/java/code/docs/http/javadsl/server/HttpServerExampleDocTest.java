/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.IncomingConnection;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Connection;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.Unmarshaller;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.IOResult;
import akka.stream.Materializer;
import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import scala.concurrent.ExecutionContextExecutor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static akka.http.javadsl.server.Directives.*;

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
  

  //#consume-entity-directive
  class Bid {
    final String userId;
    final int bid;

    Bid(String userId, int bid) {
      this.userId = userId;
      this.bid = bid; 
    }
  }
  //#consume-entity-directive

  void consumeEntityUsingEntityDirective() {
    //#consume-entity-directive
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);
    
    final Unmarshaller<HttpEntity, Bid> asBid = Jackson.unmarshaller(Bid.class);

    final Route s = path("bid", () ->
      put(() ->
        entity(asBid, bid ->
          // incoming entity is fully consumed and converted into a Bid
          complete("The bid was: " + bid)
        )
      )
    );
    //#consume-entity-directive
      }
      
  void consumeEntityUsingRawDataBytes() {
    //#consume-raw-dataBytes
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Route s =
      put(() ->
        path("lines", () ->
          withoutSizeLimit(() ->
            extractDataBytes(bytes -> {
              final CompletionStage<IOResult> res = bytes.runWith(FileIO.toPath(new File("/tmp/example.out").toPath()), materializer);

              return onComplete(() -> res, ioResult ->
                // we only want to respond once the incoming data has been handled:
                complete("Finished writing data :" + ioResult));
            })
          )
        )
      );

    //#consume-raw-dataBytes
  }
      
  void discardEntityUsingRawBytes() {
    //#discard-discardEntityBytes
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Route s =
      put(() ->
        path("lines", () ->
          withoutSizeLimit(() ->
            extractRequest(r -> {
              final CompletionStage<Done> res = r.discardEntityBytes(materializer).completionStage();

              return onComplete(() -> res, done ->
                // we only want to respond once the incoming data has been handled:
                complete("Finished writing data :" + done));
            })
          )
        )
      );
    //#discard-discardEntityBytes
  }
      
  void discardEntityManuallyCloseConnections() {
        //#discard-close-connections
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Route s =
      put(() ->
        path("lines", () ->
          withoutSizeLimit(() ->
            extractDataBytes(bytes -> {
              // Closing connections, method 1 (eager):
              // we deem this request as illegal, and close the connection right away:
              bytes.runWith(Sink.cancelled(), materializer);  // "brutally" closes the connection

              // Closing connections, method 2 (graceful):
              // consider draining connection and replying with `Connection: Close` header
              // if you want the client to close after this request/reply cycle instead:
              return respondWithHeader(Connection.create("close"), () -> 
                complete(StatusCodes.FORBIDDEN, "Not allowed!")
              );
            })
          )
        )
      );
        //#discard-close-connections
      }
    
  
}
