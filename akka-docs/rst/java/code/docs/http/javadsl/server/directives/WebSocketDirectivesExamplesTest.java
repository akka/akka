/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.NotUsed;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.SecWebSocketProtocol;
import akka.http.javadsl.model.ws.BinaryMessage;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.WSProbe;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class WebSocketDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testHandleWebSocketMessages() {
    //#handleWebSocketMessages
    final Flow<Message, Message, NotUsed> greeter = Flow.of(Message.class).mapConcat(msg -> {
      if (msg instanceof TextMessage) {
        final TextMessage tm = (TextMessage) msg;
        final TextMessage ret = TextMessage.create(Source.single("Hello ").concat(tm.getStreamedText()).concat(Source.single("!")));
        return Collections.singletonList(ret);
      } else if (msg instanceof BinaryMessage) {
        final BinaryMessage bm = (BinaryMessage) msg;
        bm.getStreamedData().runWith(Sink.ignore(), materializer());
        return Collections.emptyList();
      } else {
        throw new IllegalArgumentException("Unsupported message type!");
      }
    });

    final Route websocketRoute = path("greeter", () ->
      handleWebSocketMessages(greeter)
    );

    // create a testing probe representing the client-side
    final WSProbe wsClient = WSProbe.create(system(), materializer());

    // WS creates a WebSocket request for testing
    testRoute(websocketRoute).run(WS(Uri.create("/greeter"), wsClient.flow(), materializer()))
      .assertStatusCode(StatusCodes.SWITCHING_PROTOCOLS);

    // manually run a WS conversation
    wsClient.sendMessage("Peter");
    wsClient.expectMessage("Hello Peter!");

    wsClient.sendMessage(BinaryMessage.create(ByteString.fromString("abcdef")));
    wsClient.expectNoMessage(FiniteDuration.create(100, TimeUnit.MILLISECONDS));

    wsClient.sendMessage("John");
    wsClient.expectMessage("Hello John!");

    wsClient.sendCompletion();
    wsClient.expectCompletion();
    //#handleWebSocketMessages
  }

  @Test
  public void testHandleWebSocketMessagesForProtocol() {
    //#handleWebSocketMessagesForProtocol
    final Flow<Message, Message, NotUsed> greeterService = Flow.of(Message.class).mapConcat(msg -> {
      if (msg instanceof TextMessage) {
        final TextMessage tm = (TextMessage) msg;
        final TextMessage ret = TextMessage.create(Source.single("Hello ").concat(tm.getStreamedText()).concat(Source.single("!")));
        return Collections.singletonList(ret);
      } else if (msg instanceof BinaryMessage) {
        final BinaryMessage bm = (BinaryMessage) msg;
        bm.getStreamedData().runWith(Sink.ignore(), materializer());
        return Collections.emptyList();
      } else {
        throw new IllegalArgumentException("Unsupported message type!");
      }
    });

    final Flow<Message, Message, NotUsed> echoService = Flow.of(Message.class).buffer(1, OverflowStrategy.backpressure());

    final Route websocketMultipleProtocolRoute = path("services", () ->
      route(
        handleWebSocketMessagesForProtocol(greeterService, "greeter"),
        handleWebSocketMessagesForProtocol(echoService, "echo")
      )
    );

    // create a testing probe representing the client-side
    final WSProbe wsClient = WSProbe.create(system(), materializer());

    // WS creates a WebSocket request for testing
    testRoute(websocketMultipleProtocolRoute)
      .run(WS(Uri.create("/services"), wsClient.flow(), materializer(), Arrays.asList("other", "echo")))
      .assertHeaderExists(SecWebSocketProtocol.create("echo"));

    wsClient.sendMessage("Peter");
    wsClient.expectMessage("Peter");

    wsClient.sendMessage(BinaryMessage.create(ByteString.fromString("abcdef")));
    wsClient.expectMessage(ByteString.fromString("abcdef"));

    wsClient.sendMessage("John");
    wsClient.expectMessage("John");

    wsClient.sendCompletion();
    wsClient.expectCompletion();
    //#handleWebSocketMessagesForProtocol
  }
}
