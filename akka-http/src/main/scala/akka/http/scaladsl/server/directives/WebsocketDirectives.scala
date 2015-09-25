/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import scala.collection.immutable

import akka.http.scaladsl.model.ws.{ UpgradeToWebsocket, Message }
import akka.stream.scaladsl.Flow

trait WebsocketDirectives {
  import RouteDirectives._
  import HeaderDirectives._
  import BasicDirectives._

  /**
   * Extract the [[UpgradeToWebsocket]] header if existent. Rejects with an [[ExpectedWebsocketRequestRejection]], otherwise.
   */
  def extractUpgradeToWebsocket: Directive1[UpgradeToWebsocket] =
    optionalHeaderValueByType[UpgradeToWebsocket](()).flatMap {
      case Some(upgrade) ⇒ provide(upgrade)
      case None          ⇒ reject(ExpectedWebsocketRequestRejection)
    }

  /**
   * Extract the list of Websocket subprotocols as offered by the client in the [[Sec-Websocket-Protocol]] header if
   * this is a Websocket request. Rejects with an [[ExpectedWebsocketRequestRejection]], otherwise.
   */
  def extractOfferedWsProtocols: Directive1[immutable.Seq[String]] = extractUpgradeToWebsocket.map(_.requestedProtocols)

  /**
   * Handles Websocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebsocketRequestRejection]].
   */
  def handleWebsocketMessages(handler: Flow[Message, Message, Any]): Route =
    handleWebsocketMessagesForOptionalProtocol(handler, None)

  /**
   * Handles Websocket requests with the given handler if the given subprotocol is offered in the request and
   * rejects other requests with an [[ExpectedWebsocketRequestRejection]] or an [[UnsupportedWebsocketSubprotocolRejection]].
   */
  def handleWebsocketMessagesForProtocol(handler: Flow[Message, Message, Any], subprotocol: String): Route =
    handleWebsocketMessagesForOptionalProtocol(handler, Some(subprotocol))

  /**
   * Handles Websocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebsocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any Websocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a Websocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the Websocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebsocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebsocketMessage` Routes.
   */
  def handleWebsocketMessagesForOptionalProtocol(handler: Flow[Message, Message, Any], subprotocol: Option[String]): Route =
    extractUpgradeToWebsocket { upgrade ⇒
      if (subprotocol.forall(sub ⇒ upgrade.requestedProtocols.exists(_ equalsIgnoreCase sub)))
        complete(upgrade.handleMessages(handler, subprotocol))
      else
        reject(UnsupportedWebsocketSubprotocolRejection(subprotocol.get)) // None.forall == true
    }
}
