/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#websocket-example-using-core

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.NotUsed;
import akka.http.javadsl.ConnectHttp;
import akka.japi.Function;
import akka.japi.JavaPartialFunction;

import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocket;

@SuppressWarnings("Convert2MethodRef")
public class WebSocketCoreExample {
  
  //#websocket-handling
  public static HttpResponse handleRequest(HttpRequest request) {
    System.out.println("Handling request to " + request.getUri());

    if (request.getUri().path().equals("/greeter")) {
      final Flow<Message, Message, NotUsed> greeterFlow = greeter();
      return WebSocket.handleWebSocketRequestWith(request, greeterFlow);
    } else {
      return HttpResponse.create().withStatus(404);
    }
  }
  //#websocket-handling

  public static void main(String[] args) throws Exception {
    ActorSystem system = ActorSystem.create();

    try {
      final Materializer materializer = ActorMaterializer.create(system);

      final Function<HttpRequest, HttpResponse> handler = request -> handleRequest(request);
      CompletionStage<ServerBinding> serverBindingFuture =
        Http.get(system).bindAndHandleSync(
          handler, ConnectHttp.toHost("localhost", 8080), materializer);

      // will throw if binding fails
      serverBindingFuture.toCompletableFuture().get(1, TimeUnit.SECONDS);
      System.out.println("Press ENTER to stop.");
      new BufferedReader(new InputStreamReader(System.in)).readLine();
    } finally {
      system.terminate();
    }
  }

  //#websocket-handler

  /**
   * A handler that treats incoming messages as a name,
   * and responds with a greeting to that name
   */
  public static Flow<Message, Message, NotUsed> greeter() {
    return
      Flow.<Message>create()
        .collect(new JavaPartialFunction<Message, Message>() {
          @Override
          public Message apply(Message msg, boolean isCheck) throws Exception {
            if (isCheck) {
              if (msg.isText()) {
                return null;
              } else {
                throw noMatch();
              }
            } else {
              return handleTextMessage(msg.asTextMessage());
            }
          }
        });
  }

  public static TextMessage handleTextMessage(TextMessage msg) {
    if (msg.isStrict()) // optimization that directly creates a simple response...
    {
      return TextMessage.create("Hello " + msg.getStrictText());
    } else // ... this would suffice to handle all text messages in a streaming fashion
    {
      return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
    }
  }
  //#websocket-handler
}
//#websocket-example-using-core
