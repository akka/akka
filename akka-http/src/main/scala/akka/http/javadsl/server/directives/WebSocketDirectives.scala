/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.util.{ List ⇒ JList }
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

import JavaScalaTypeEquivalence._

import akka.http.javadsl.model.ws.Message
import akka.http.javadsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Flow

abstract class WebSocketDirectives extends SecurityDirectives {
  /**
   * Extract the [[UpgradeToWebSocket]] header if existent. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractUpgradeToWebSocket(inner: JFunction[UpgradeToWebSocket, Route]): Route = ScalaRoute {
    D.extractUpgradeToWebSocket { header ⇒
      inner.apply(header).toScala
    }
  }

  /**
   * Extract the list of WebSocket subprotocols as offered by the client in the [[Sec-WebSocket-Protocol]] header if
   * this is a WebSocket request. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractOfferedWsProtocols(inner: JFunction[JList[String], Route]): Route = ScalaRoute {
    D.extractOfferedWsProtocols { list ⇒
      inner.apply(list.asJava).toScala
    }
  }

  /**
   * Handles WebSocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebSocketRequestRejection]].
   */
  def handleWebSocketMessages[T](handler: Flow[Message, Message, T]): Route = ScalaRoute {
    D.handleWebSocketMessages(handler)
  }

  /**
   * Handles WebSocket requests with the given handler if the given subprotocol is offered in the request and
   * rejects other requests with an [[ExpectedWebSocketRequestRejection]] or an [[UnsupportedWebSocketSubprotocolRejection]].
   */
  def handleWebSocketMessagesForProtocol(handler: Flow[Message, Message, Any], subprotocol: String): Route = ScalaRoute {
    D.handleWebSocketMessagesForProtocol(handler, subprotocol)
  }

  /**
   * Handles WebSocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebSocketRequestRejection]].
   *
   * If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
   * `Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
   * announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
   * the request is rejected with an [[UnsupportedWebSocketSubprotocolRejection]] rejection.
   *
   * To support several subprotocols you may chain several `handleWebSocketMessage` Routes.
   */
  def handleWebSocketMessagesForOptionalProtocol(handler: Flow[Message, Message, Any], subprotocol: Optional[String]): Route = ScalaRoute {
    D.handleWebSocketMessagesForOptionalProtocol(handler, subprotocol.asScala)
  }

}
