/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure
import akka.http.javadsl.model.ws.Message
import akka.stream.javadsl.Flow

abstract class WebSocketDirectives extends SchemeDirectives {
  /**
   * Handles websocket requests with the given handler and rejects other requests with a
   * [[akka.http.scaladsl.server.ExpectedWebSocketRequestRejection]].
   */
  def handleWebSocketMessages(handler: Flow[Message, Message, _]): Route =
    RouteStructure.HandleWebSocketMessages(handler)
}
