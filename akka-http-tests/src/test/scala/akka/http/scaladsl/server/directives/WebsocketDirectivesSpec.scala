/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server.directives

import akka.util.ByteString

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Source, Sink, Flow }

import akka.http.scaladsl.testkit.WSProbe

import akka.http.scaladsl.model.headers.`Sec-WebSocket-Protocol`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.{ UnsupportedWebsocketSubprotocolRejection, ExpectedWebsocketRequestRejection, Route, RoutingSpec }

class WebsocketDirectivesSpec extends RoutingSpec {
  "the handleWebsocketMessages directive" should {
    "handle websocket requests" in {
      val wsClient = WSProbe()

      WS("http://localhost/", wsClient.flow) ~> websocketRoute ~>
        check {
          isWebsocketUpgrade shouldEqual true
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
          expectWebsocketUpgradeWithProtocol { protocol ⇒
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
          case UnsupportedWebsocketSubprotocolRejection(p) ⇒ p
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
        rejection shouldEqual ExpectedWebsocketRequestRejection
      }

      Get("http://localhost/") ~> Route.seal(websocketRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "Expected Websocket Upgrade request"
      }
    }
  }

  def websocketRoute = handleWebsocketMessages(greeter)
  def websocketMultipleProtocolRoute =
    handleWebsocketMessagesForProtocol(echo, "echo") ~
      handleWebsocketMessagesForProtocol(greeter, "greeter")

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
