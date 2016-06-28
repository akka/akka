/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.ConnectHttp
import akka.http.javadsl.Http
import akka.http.javadsl.IncomingConnection
import akka.http.javadsl.ServerBinding
import akka.http.javadsl.model._
import akka.japi.function.Function
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.javadsl.Flow
import akka.stream.javadsl.Sink
import akka.stream.javadsl.Source
import akka.util.ByteString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

@SuppressWarnings(Array("unused")) object HttpServerExampleDocTest {
  @throws[Exception]
  def bindingExample {
    val system: ActorSystem = ActorSystem.create
    val materializer: Materializer = ActorMaterializer.create(system)
    val serverSource: Source[IncomingConnection, CompletionStage[ServerBinding]] = Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer)
    val serverBindingFuture: CompletionStage[ServerBinding] = serverSource.to(Sink.foreach(connection -> {
      System.out.println("Accepted new connection from " + connection.remoteAddress());
      // ... and then actually handle the connection
    })).run(materializer)
    serverBindingFuture.toCompletableFuture.get(3, TimeUnit.SECONDS)
  }

  @throws[Exception]
  def bindingFailureExample {
    val system: ActorSystem = ActorSystem.create
    val materializer: Materializer = ActorMaterializer.create(system)
    val serverSource: Source[IncomingConnection, CompletionStage[ServerBinding]] = Http.get(system).bind(ConnectHttp.toHost("localhost", 80), materializer)
    val serverBindingFuture: CompletionStage[ServerBinding] = serverSource.to(Sink.foreach(connection -> {
      System.out.println("Accepted new connection from " + connection.remoteAddress());
      // ... and then actually handle the connection
    })).run(materializer)
    serverBindingFuture.whenCompleteAsync((binding, failure) -> {
      // possibly report the failure somewhere...
    }, system.dispatcher)
    serverBindingFuture.toCompletableFuture.get(3, TimeUnit.SECONDS)
  }

  @throws[Exception]
  def connectionSourceFailureExample {
    val system: ActorSystem = ActorSystem.create
    val materializer: Materializer = ActorMaterializer.create(system)
    val serverSource: Source[IncomingConnection, CompletionStage[ServerBinding]] = Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer)
    val failureDetection: Flow[IncomingConnection, IncomingConnection, NotUsed] = Flow.of(classOf[IncomingConnection]).watchTermination((notUsed, termination) -> {
      termination.whenComplete((done, cause) -> {
        if (cause != null) {
          // signal the failure to external monitoring service!
        }
      });
      return NotUsed.getInstance();
    })
    val serverBindingFuture: CompletionStage[ServerBinding] = serverSource.via(failureDetection).to(Sink.foreach(connection -> {
      System.out.println("Accepted new connection from " + connection.remoteAddress());
      // ... and then actually handle the connection
    })).run(materializer)
    serverBindingFuture.toCompletableFuture.get(3, TimeUnit.SECONDS)
  }

  @throws[Exception]
  def connectionStreamFailureExample {
    val system: ActorSystem = ActorSystem.create
    val materializer: Materializer = ActorMaterializer.create(system)
    val serverSource: Source[IncomingConnection, CompletionStage[ServerBinding]] = Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer)
    val failureDetection: Flow[HttpRequest, HttpRequest, NotUsed] = Flow.of(classOf[HttpRequest]).watchTermination((notUsed, termination) -> {
      termination.whenComplete((done, cause) -> {
        if (cause != null) {
          // signal the failure to external monitoring service!
        }
      });
      return NotUsed.getInstance();
    })
    val httpEcho: Flow[HttpRequest, HttpResponse, NotUsed] = Flow.of(classOf[HttpRequest]).via(failureDetection).map(request -> {
      Source < ByteString, Object > bytes = request.entity().getDataBytes();
      HttpEntity.Chunked entity = HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, bytes);

      return HttpResponse.create()
        .withEntity(entity);
    })
    val serverBindingFuture: CompletionStage[ServerBinding] = serverSource.to(Sink.foreach(conn -> {
      System.out.println("Accepted new connection from " + conn.remoteAddress());
      conn.handleWith(httpEcho, materializer);
    })).run(materializer)
    serverBindingFuture.toCompletableFuture.get(3, TimeUnit.SECONDS)
  }

  @throws[Exception]
  def fullServerExample {
    val system: ActorSystem = ActorSystem.create
    try {
      val materializer: Materializer = ActorMaterializer.create(system)
      val serverSource: Source[IncomingConnection, CompletionStage[ServerBinding]] = Http.get(system).bind(ConnectHttp.toHost("localhost", 8080), materializer)
      val requestHandler: Function[HttpRequest, HttpResponse] = new Function[HttpRequest, HttpResponse]() {
        final private val NOT_FOUND: HttpResponse = HttpResponse.create.withStatus(404).withEntity("Unknown resource!")
        @throws[Exception]

        def apply(request: HttpRequest): HttpResponse = {
          val uri: Uri = request.getUri
          if (request.method eq HttpMethods.GET) {
            if (uri.path == "/") {
              return HttpResponse.create.withEntity(ContentTypes.TEXT_HTML_UTF8, "<html><body>Hello world!</body></html>")
            }
            else if (uri.path == "/hello") {
              val name: String = uri.query.get("name").orElse("Mister X")
              return HttpResponse.create.withEntity("Hello " + name + "!")
            }
            else if (uri.path == "/ping") {
              return HttpResponse.create.withEntity("PONG!")
            }
            else {
              return NOT_FOUND
            }
          }
          else {
            return NOT_FOUND
          }
        }
      }
      val serverBindingFuture: CompletionStage[ServerBinding] = serverSource.to(Sink.foreach(connection -> {
        System.out.println("Accepted new connection from " + connection.remoteAddress());

        connection.handleWithSyncHandler(requestHandler, materializer);
        // this is equivalent to
        //connection.handleWith(Flow.of(HttpRequest.class).map(requestHandler), materializer);
      })).run(materializer)
      serverBindingFuture.toCompletableFuture.get(1, TimeUnit.SECONDS)
      System.out.println("Press ENTER to stop.")
      new BufferedReader(new InputStreamReader(System.in)).readLine
    } finally {
      system.terminate
    }
  }

  @throws[Exception]
  def main(args: Array[String]) {
    fullServerExample
  }
}
