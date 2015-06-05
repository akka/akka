/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.ws.{ UpgradeToWebsocket, Message }
import akka.stream.scaladsl.Flow

trait WebsocketDirectives {
  import BasicDirectives._
  import RouteDirectives._
  import HeaderDirectives._

  /**
   * Handles websocket requests with the given handler and rejects other requests with a
   * [[ExpectedWebsocketRequestRejection]].
   */
  def handleWebsocketMessages(handler: Flow[Message, Message, Any]): Route =
    optionalHeaderValueByType[UpgradeToWebsocket]() {
      case Some(upgrade) ⇒ complete(upgrade.handleMessages(handler))
      case None          ⇒ reject(ExpectedWebsocketRequestRejection)
    }
}
