/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import scala.concurrent.duration._

import akka.util.ByteString

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Sink, Source, Flow }

import docs.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.model.ws.{ TextMessage, Message, BinaryMessage }
import akka.http.scaladsl.testkit.WSProbe

class WebSocketDirectivesExamplesSpec extends RoutingSpec {
  "greeter-service" in {
    //#greeter-service
    def greeter: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case tm: TextMessage =>
          TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          Nil
      }
    val websocketRoute =
      path("greeter") {
        handleWebSocketMessages(greeter)
      }

    // tests:
    // create a testing probe representing the client-side
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/greeter", wsClient.flow) ~> websocketRoute ~>
      check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        // manually run a WS conversation
        wsClient.sendMessage("Peter")
        wsClient.expectMessage("Hello Peter!")

        wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
        wsClient.expectNoMessage(100.millis)

        wsClient.sendMessage("John")
        wsClient.expectMessage("Hello John!")

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    //#greeter-service
  }

  "handle-multiple-protocols" in {
    //#handle-multiple-protocols
    def greeterService: Flow[Message, Message, Any] =
      Flow[Message].mapConcat {
        case tm: TextMessage =>
          TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          Nil
      }

    def echoService: Flow[Message, Message, Any] =
      Flow[Message]
        // needed because a noop flow hasn't any buffer that would start processing in tests
        .buffer(1, OverflowStrategy.backpressure)

    def websocketMultipleProtocolRoute =
      path("services") {
        handleWebSocketMessagesForProtocol(greeterService, "greeter") ~
          handleWebSocketMessagesForProtocol(echoService, "echo")
      }

    // tests:
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/services", wsClient.flow, List("other", "echo")) ~>
      websocketMultipleProtocolRoute ~>
      check {
        expectWebSocketUpgradeWithProtocol { protocol =>
          protocol shouldEqual "echo"

          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Peter")

          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
          wsClient.expectMessage(ByteString("abcdef"))

          wsClient.sendMessage("John")
          wsClient.expectMessage("John")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
      }
    //#handle-multiple-protocols
  }

  "extractUpgradeToWebSocket" in {
    //#extractUpgradeToWebSocket
    def echoService: Flow[Message, Message, Any] =
      Flow[Message]
        // needed because a noop flow hasn't any buffer that would start processing in tests
        .buffer(1, OverflowStrategy.backpressure)

    def route =
      path("services") {
        extractUpgradeToWebSocket { upgrade ⇒
          complete(upgrade.handleMessages(echoService, Some("echo")))
        }
      }

    // tests:
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/services", wsClient.flow, Nil) ~> route ~> check {
      expectWebSocketUpgradeWithProtocol { protocol =>
        protocol shouldEqual "echo"
        wsClient.sendMessage("ping")
        wsClient.expectMessage("ping")
        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    }
    //#extractUpgradeToWebSocket
  }

  "extractOfferedWsProtocols" in {
    //#extractOfferedWsProtocols
    def echoService: Flow[Message, Message, Any] =
      Flow[Message]
        // needed because a noop flow hasn't any buffer that would start processing in tests
        .buffer(1, OverflowStrategy.backpressure)

    def route =
      path("services") {
        extractOfferedWsProtocols { protocols =>
          handleWebSocketMessagesForOptionalProtocol(echoService, protocols.headOption)
        }
      }

    // tests:
    val wsClient = WSProbe()

    // WS creates a WebSocket request for testing
    WS("/services", wsClient.flow, List("echo", "alfa", "kilo")) ~> route ~> check {
      expectWebSocketUpgradeWithProtocol { protocol =>
        protocol shouldEqual "echo"
        wsClient.sendMessage("ping")
        wsClient.expectMessage("ping")
        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }
    }
    //#extractOfferedWsProtocols
  }
}
