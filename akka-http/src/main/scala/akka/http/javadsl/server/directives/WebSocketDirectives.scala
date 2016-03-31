/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.util.{ List ⇒ JList }
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import akka.NotUsed
import scala.collection.JavaConverters._
import akka.http.scaladsl.model.{ ws ⇒ s }
import akka.http.javadsl.model.ws.Message
import akka.http.javadsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Flow
import akka.stream.scaladsl

abstract class WebSocketDirectives extends SecurityDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Extract the [[UpgradeToWebSocket]] header if existent. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractUpgradeToWebSocket(inner: JFunction[UpgradeToWebSocket, Route]): Route = RouteAdapter {
    D.extractUpgradeToWebSocket { header ⇒
      inner.apply(header).delegate
    }
  }

  /**
   * Extract the list of WebSocket subprotocols as offered by the client in the [[Sec-WebSocket-Protocol]] header if
   * this is a WebSocket request. Rejects with an [[ExpectedWebSocketRequestRejection]], otherwise.
   */
  def extractOfferedWsProtocols(inner: JFunction[JList[String], Route]): Route = RouteAdapter {
    D.extractOfferedWsProtocols { list ⇒
      inner.apply(list.asJava).delegate
    }
  }

  /**
   * Handles WebSocket requests with the given handler and rejects other requests with an
   * [[ExpectedWebSocketRequestRejection]].
   */
  def handleWebSocketMessages[T](handler: Flow[Message, Message, T]): Route = RouteAdapter {
    D.handleWebSocketMessages(adapt(handler))
  }

  /**
   * Handles WebSocket requests with the given handler if the given subprotocol is offered in the request and
   * rejects other requests with an [[ExpectedWebSocketRequestRejection]] or an [[UnsupportedWebSocketSubprotocolRejection]].
   */
  def handleWebSocketMessagesForProtocol(handler: Flow[Message, Message, Any], subprotocol: String): Route = RouteAdapter {
    val adapted = scaladsl.Flow[s.Message].map(_.asJava).via(handler).map(_.asScala)
    D.handleWebSocketMessagesForProtocol(adapted, subprotocol)
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
  def handleWebSocketMessagesForOptionalProtocol(handler: Flow[Message, Message, Any], subprotocol: Optional[String]): Route = RouteAdapter {
    val adapted = scaladsl.Flow[s.Message].map(_.asJava).via(handler).map(_.asScala)
    D.handleWebSocketMessagesForOptionalProtocol(adapted, subprotocol.asScala)
  }

  // TODO this is because scala Message does not extend java Message - we could fix that, but http-core is stable
  private def adapt[T](handler: Flow[Message, Message, T]): scaladsl.Flow[s.Message, s.Message, NotUsed] = {
    scaladsl.Flow[s.Message].map(_.asJava).via(handler).map(_.asScala)
  }
}
