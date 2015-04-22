/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.directives

import akka.http.engine.ws.InternalCustomHeader
import akka.http.model
import akka.http.model.headers.{ Connection, UpgradeProtocol, Upgrade }
import akka.http.model.{ HttpRequest, StatusCodes, HttpResponse }
import akka.http.model.ws.{ Message, UpgradeToWebsocket }
import akka.http.server.{ Route, RoutingSpec }
import akka.http.util.Rendering
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

import scala.collection.immutable.Seq

class WebsocketDirectivesSpec extends RoutingSpec {
  "the handleWebsocketMessages directive" should {
    "handle websocket requests" in {
      Get("http://localhost/") ~> Upgrade(List(UpgradeProtocol("websocket"))) ~>
        emulateHttpCore ~> Route.seal(handleWebsocketMessages(Flow[Message])) ~>
        check {
          status shouldEqual StatusCodes.SwitchingProtocols
        }
    }
    "reject non-websocket requests" in {
      Get("http://localhost/") ~> emulateHttpCore ~> Route.seal(handleWebsocketMessages(Flow[Message])) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "Expected Websocket Upgrade request"
      }
    }
  }

  /** Only checks for upgrade header and then adds UpgradeToWebsocket mock header */
  def emulateHttpCore(req: HttpRequest): HttpRequest =
    req.header[Upgrade] match {
      case Some(upgrade) if upgrade.hasWebsocket ⇒ req.copy(headers = req.headers :+ upgradeToWebsocketHeaderMock)
      case _                                     ⇒ req
    }
  def upgradeToWebsocketHeaderMock: UpgradeToWebsocket =
    new InternalCustomHeader("UpgradeToWebsocketMock") with UpgradeToWebsocket {
      def requestedProtocols: Seq[String] = Nil

      def handleMessages(handlerFlow: Flow[Message, Message, Any], subprotocol: Option[String])(implicit mat: FlowMaterializer): HttpResponse =
        HttpResponse(StatusCodes.SwitchingProtocols)
    }
}
