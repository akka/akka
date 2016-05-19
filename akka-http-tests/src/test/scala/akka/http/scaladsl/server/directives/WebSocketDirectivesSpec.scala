/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.util.ByteString

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Source, Sink, Flow }

import akka.http.scaladsl.testkit.WSProbe
import akka.http.scaladsl.model.headers.{ HttpOrigin, HttpOriginRange, `Sec-WebSocket-Protocol` }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.{ UnsupportedWebSocketSubprotocolRejection, ExpectedWebSocketRequestRejection, Route, RoutingSpec }

class WebSocketDirectivesSpec extends RoutingSpec {
  "the handleWebSocketMessages directive" should {
    "handle websocket requests" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> websocketRoute ~>
        check {
          isWebSocketUpgrade shouldEqual true
          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Hello Peter!")

          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
          // wsClient.expectNoMessage() // will be checked implicitly by next expectation

          wsClient.sendMessage("John")
          wsClient.expectMessage("Hello John!")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }
    "choose subprotocol from offered ones" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow, List("other", "echo", "greeter")) ~> websocketMultipleProtocolRoute ~>
        check {
          expectWebSocketUpgradeWithProtocol { protocol ⇒
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
    }
    "reject websocket requests if no subprotocol matches" in {
      WS("http://localhost/", Flow[Message], List("other")) ~> websocketMultipleProtocolRoute ~> check {
        rejections.collect {
          case UnsupportedWebSocketSubprotocolRejection(p) ⇒ p
        }.toSet shouldEqual Set("greeter", "echo")
      }

      WS("http://localhost/", Flow[Message], List("other")) ~> Route.seal(websocketMultipleProtocolRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "None of the websocket subprotocols offered in the request are supported. Supported are 'echo','greeter'."
        header[`Sec-WebSocket-Protocol`].get.protocols.toSet shouldEqual Set("greeter", "echo")
      }
    }
    "reject non-websocket requests" in {
      Get("http://localhost/") ~> websocketRoute ~> check {
        rejection shouldEqual ExpectedWebSocketRequestRejection
      }

      Get("http://localhost/") ~> Route.seal(websocketRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "Expected WebSocket Upgrade request"
      }
    }
    "handle websocket request with correct origin header value" in {
      val wsClient = WSProbe()

      import akka.http.scaladsl.model.headers.Origin
      val originHeader = Origin(HttpOrigin("http://localhost:8080"))

      WS("http://localhost/", wsClient.flow) ~> originHeader ~> websocketRouteWithSameOriginCheck ~>
        check {
          isWebSocketUpgrade shouldEqual true
          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Hello Peter!")

          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))

          wsClient.sendMessage("John")
          wsClient.expectMessage("Hello John!")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }
    "reject websocket requests with missed origin header" in {
      WS("http://localhost/", Flow[Message], List("other")) ~> websocketRouteWithSameOriginCheck ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[String] should include("missing")
      }
    }
    "reject websocket requests with malformed origin header value" in {
      import akka.http.scaladsl.model.headers.Origin
      val malformedHeader = Origin(HttpOrigin("http://example.com"))

      WS("http://localhost/", Flow[Message], List("other")) ~> malformedHeader ~> websocketRouteWithSameOriginCheck ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[String] should include("malformed")
      }
    }
  }

  def websocketRoute = handleWebSocketMessages(greeter)
  def websocketMultipleProtocolRoute =
    handleWebSocketMessagesForProtocol(echo, "echo") ~
      handleWebSocketMessagesForProtocol(greeter, "greeter")

  def websocketRouteWithSameOriginCheck =
    checkSameOrigin(allowed = HttpOriginRange(HttpOrigin("http://localhost:8080")))(websocketRoute)

  def greeter: Flow[Message, Message, Any] =
    Flow[Message].mapConcat {
      case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
      case bm: BinaryMessage ⇒ // ignore binary messages
        bm.dataStream.runWith(Sink.ignore)
        Nil
    }

  def echo: Flow[Message, Message, Any] =
    Flow[Message]
      .buffer(1, OverflowStrategy.backpressure) // needed because a noop flow hasn't any buffer that would start processing
}
