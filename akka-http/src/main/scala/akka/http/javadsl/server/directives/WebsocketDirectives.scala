/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure
import akka.http.javadsl.model.ws.Message
import akka.stream.javadsl.Flow

abstract class WebSocketDirectives extends SchemeDirectives {
  /**
   * Handles websocket requests with the given handler and rejects other requests with a
   * [[ExpectedWebSocketRequestRejection]].
   */
  def handleWebSocketMessages(handler: Flow[Message, Message, _]): Route =
    RouteStructure.HandleWebSocketMessages(handler)
}
