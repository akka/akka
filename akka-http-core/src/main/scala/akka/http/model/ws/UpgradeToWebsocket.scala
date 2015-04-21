/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.ws

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

import akka.http.model.{ HttpHeader, HttpResponse }

/**
 * A custom header that will be added to an Websocket upgrade HttpRequest that
 * enables a request handler to upgrade this connection to a Websocket connection and
 * registers a Websocket handler.
 *
 * FIXME: needs to be able to choose subprotocols as possibly agreed on in the websocket handshake
 */
trait UpgradeToWebsocket extends HttpHeader {
  /**
   * The high-level interface to create a Websocket server based on "messages".
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to Websocket and
   * use the supplied handler to handle incoming Websocket messages.
   */
  def handleMessages(handlerFlow: Flow[Message, Message, Any])(implicit mat: FlowMaterializer): HttpResponse
}
