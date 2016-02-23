/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Authorization;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocketRequest;
import akka.http.javadsl.model.ws.WebSocketUpgradeResponse;
import akka.japi.Pair;
import akka.japi.function.Procedure;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.util.concurrent.CompletionStage;

@SuppressWarnings("unused")
public class WebSocketClientExampleTest {

  // compile only test
  public void testSingleWebSocketRequest() {
    //#single-WebSocket-request
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);
    Http http = Http.get(system);

    // print each incoming text message
    // would throw exception on non strict or binary message
    Sink<Message, CompletionStage<Done>> printSink =
      Sink.foreach((message) ->
        System.out.println("Got message: " + message.asTextMessage().getStrictText())
      );

    // send this as a message over the WebSocket
    Source<Message, NotUsed> helloSource =
      Source.single(TextMessage.create("hello world"));

    // the CompletionStage<Done> is the materialized value of Sink.foreach
    // and it is completed when the stream completes
    Flow<Message, Message, CompletionStage<Done>> flow =
      Flow.fromSinkAndSourceMat(printSink, helloSource, Keep.left());

    Pair<CompletionStage<WebSocketUpgradeResponse>, CompletionStage<Done>> pair =
      http.singleWebSocketRequest(
        WebSocketRequest.create("ws://echo.websocket.org"),
        flow,
        materializer
      );

    // The first value in the pair is a CompletionStage<WebSocketUpgradeResponse> that
    // completes when the WebSocket request has connected successfully (or failed)
    CompletionStage<Done> connected = pair.first().thenApply(upgrade -> {
      // just like a regular http request we can get 404 NotFound,
      // with a response body, that will be available from upgrade.response
      if (upgrade.response().status().equals(StatusCodes.OK)) {
        return Done.getInstance();
      } else {
        throw new RuntimeException("Connection failed: " + upgrade.response().status());
      }
    });

    // the second value is the completion of the sink from above
    // in other words, it completes when the WebSocket disconnects
    CompletionStage<Done> closed = pair.second();

    // in a real application you would not side effect here
    // and handle errors more carefully
    connected.thenAccept(done -> System.out.println("Connected"));
    closed.thenAccept(done -> System.out.println("Connection closed"));

    //#single-WebSocket-request
  }

  // compile time only test
  public void testAuthorizedSingleWebSocketRequest() {
    Materializer materializer = null;
    Http http = null;

    Flow<Message, Message, NotUsed> flow = null;

    //#authorized-single-WebSocket-request
    http.singleWebSocketRequest(
      WebSocketRequest.create("ws://example.com:8080/some/path")
        .addHeader(Authorization.basic("johan", "correcthorsebatterystaple")),
      flow,
      materializer);
    //#authorized-single-WebSocket-request
  }

  // compile time only test
  public void testWebSocketClientFlow() {
    //#WebSocket-client-flow
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);
    Http http = Http.get(system);

    // print each incoming text message
    // would throw exception on non strict or binary message
    Sink<Message, CompletionStage<Done>> printSink =
      Sink.foreach((message) ->
          System.out.println("Got message: " + message.asTextMessage().getStrictText())
      );

    // send this as a message over the WebSocket
    Source<Message, NotUsed> helloSource =
      Source.single(TextMessage.create("hello world"));


    Flow<Message, Message, CompletionStage<WebSocketUpgradeResponse>> webSocketFlow =
      http.webSocketClientFlow(WebSocketRequest.create("ws://echo.websocket.org"));


    Pair<CompletionStage<WebSocketUpgradeResponse>, CompletionStage<Done>> pair =
      helloSource.viaMat(webSocketFlow, Keep.right())
        .toMat(printSink, Keep.both())
        .run(materializer);


    // The first value in the pair is a CompletionStage<WebSocketUpgradeResponse> that
    // completes when the WebSocket request has connected successfully (or failed)
    CompletionStage<WebSocketUpgradeResponse> upgradeCompletion = pair.first();

    // the second value is the completion of the sink from above
    // in other words, it completes when the WebSocket disconnects
    CompletionStage<Done> closed = pair.second();

    CompletionStage<Done> connected = upgradeCompletion.thenApply(upgrade->
    {
      // just like a regular http request we can get 404 NotFound,
      // with a response body, that will be available from upgrade.response
      if (upgrade.response().status().equals(StatusCodes.OK)) {
        return Done.getInstance();
      } else {
        throw new RuntimeException(("Connection failed: " + upgrade.response().status()));
      }
    });

    // in a real application you would not side effect here
    // and handle errors more carefully
    connected.thenAccept(done -> System.out.println("Connected"));
    closed.thenAccept(done -> System.out.println("Connection closed"));

    //#WebSocket-client-flow
  }



  }
