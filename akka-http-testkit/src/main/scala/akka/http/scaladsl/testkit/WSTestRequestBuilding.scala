/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.http.impl.engine.ws.InternalCustomHeader
import akka.http.scaladsl.model.headers.{ UpgradeProtocol, Upgrade, `Sec-WebSocket-Protocol` }
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpRequest, Uri }
import akka.http.scaladsl.model.ws.{ UpgradeToWebSocket, Message }
import scala.collection.immutable
import akka.stream.{ Graph, FlowShape }
import akka.stream.scaladsl.Flow

trait WSTestRequestBuilding { self: RouteTest ⇒
  def WS(uri: Uri, clientSideHandler: Flow[Message, Message, Any], subprotocols: Seq[String] = Nil)(): HttpRequest =
    HttpRequest(uri = uri)
      .addHeader(new InternalCustomHeader("UpgradeToWebSocketTestHeader") with UpgradeToWebSocket {
        def requestedProtocols: immutable.Seq[String] = subprotocols.toList

        def handleMessages(handlerFlow: Graph[FlowShape[Message, Message], Any], subprotocol: Option[String]): HttpResponse = {
          clientSideHandler.join(handlerFlow).run()
          HttpResponse(StatusCodes.SwitchingProtocols,
            headers =
              Upgrade(UpgradeProtocol("websocket") :: Nil) ::
                subprotocol.map(p ⇒ `Sec-WebSocket-Protocol`(p :: Nil)).toList)
        }
      })
}
