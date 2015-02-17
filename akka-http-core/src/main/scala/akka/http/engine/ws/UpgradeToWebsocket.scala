/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.ws

import akka.http.model.HttpResponse
import akka.http.model.headers.CustomHeader
import akka.stream.scaladsl.Flow

/**
 * A custom header that will be added to an Websocket upgrade HttpRequest that
 * enables a request handler to upgrade this connection to a Websocket connection and
 * registers a Websocket handler.
 */
trait UpgradeToWebsocket extends CustomHeader {
  /**
   * The high-level interface to create a Websocket server based on "messages".
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to Websocket and
   * use the supplied handler to handle incoming Websocket messages.
   */
  def handleMessages(handlerFlow: Flow[Message, Message]): HttpResponse =
    handleFrames(WebSocket.websocketFrameHandler(handlerFlow))

  /**
   * The low-level interface to create Websocket server based on "frames".
   * The user needs to handle control frames manually in this case.
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to Websocket and
   * use the supplied handler to handle incoming Websocket frames.
   */
  def handleFrames(handlerFlow: Flow[Frame, Frame]): HttpResponse
}