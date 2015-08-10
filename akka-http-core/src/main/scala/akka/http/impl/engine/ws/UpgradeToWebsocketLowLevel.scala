/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.{ Message, UpgradeToWebsocket }
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

/**
 * Currently internal API to handle FrameEvents directly.
 *
 * INTERNAL API
 */
private[http] abstract class UpgradeToWebsocketLowLevel extends InternalCustomHeader("UpgradeToWebsocket") with UpgradeToWebsocket {
  /**
   * The low-level interface to create Websocket server based on "frames".
   * The user needs to handle control frames manually in this case.
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to Websocket and
   * use the supplied handler to handle incoming Websocket frames.
   *
   * INTERNAL API (for now)
   */
  private[http] def handleFrames(handlerFlow: Flow[FrameEvent, FrameEvent, Any], subprotocol: Option[String] = None): HttpResponse

  override def handleMessages(handlerFlow: Flow[Message, Message, Any], subprotocol: Option[String] = None): HttpResponse =
    handleFrames(Websocket.stack(serverSide = true).join(handlerFlow), subprotocol)
}
