/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.testkit

import akka.http.impl.engine.ws.InternalCustomHeader
import akka.http.scaladsl.model.headers.{ UpgradeProtocol, Upgrade, `Sec-WebSocket-Protocol` }
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpRequest, Uri }
import akka.http.scaladsl.model.ws.{ UpgradeToWebsocket, Message }
import akka.stream.scaladsl.Flow

import scala.collection.immutable

trait WSTestRequestBuilding { self: RouteTest ⇒
  def WS(uri: Uri, clientSideHandler: Flow[Message, Message, Any], subprotocols: Seq[String] = Nil)(): HttpRequest =
    HttpRequest(uri = uri)
      .addHeader(new InternalCustomHeader("UpgradeToWebsocketTestHeader") with UpgradeToWebsocket {
        def requestedProtocols: immutable.Seq[String] = subprotocols.toList

        def handleMessages(handlerFlow: Flow[Message, Message, Any], subprotocol: Option[String]): HttpResponse = {
          clientSideHandler.join(handlerFlow).run()
          HttpResponse(StatusCodes.SwitchingProtocols,
            headers =
              Upgrade(UpgradeProtocol("websocket") :: Nil) ::
                subprotocol.map(p ⇒ `Sec-WebSocket-Protocol`(p :: Nil)).toList)
        }
      })
}
