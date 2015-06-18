/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure
import akka.http.javadsl.model.ws.Message
import akka.stream.javadsl.Flow

abstract class WebsocketDirectives extends SchemeDirectives {
  /**
   * Handles websocket requests with the given handler and rejects other requests with a
   * [[ExpectedWebsocketRequestRejection]].
   */
  def handleWebsocketMessages(handler: Flow[Message, Message, Any]): Route =
    RouteStructure.HandleWebsocketMessages(handler)
}
