/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import akka.http.javadsl.model.{ Uri, HttpHeader }
import akka.http.scaladsl.model.ws.{ WebSocketRequest ⇒ ScalaWebSocketRequest }

/**
 * Represents a WebSocket request. Use `WebSocketRequest.create` to create a request
 * for a target URI and then use `addHeader` or `requestSubprotocol` to set optional
 * details.
 */
abstract class WebSocketRequest {
  /**
   * Return a copy of this request that contains the given additional header.
   */
  def addHeader(header: HttpHeader): WebSocketRequest

  /**
   * Return a copy of this request that will require that the server uses the
   * given WebSocket subprotocol.
   */
  def requestSubprotocol(subprotocol: String): WebSocketRequest

  def asScala: ScalaWebSocketRequest
}
object WebSocketRequest {
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Creates a WebSocketRequest to a target URI. Use the methods on `WebSocketRequest`
   * to specify further details.
   */
  def create(uri: Uri): WebSocketRequest =
    wrap(ScalaWebSocketRequest(uri.asScala))

  /**
   * Creates a WebSocketRequest to a target URI. Use the methods on `WebSocketRequest`
   * to specify further details.
   */
  def create(uriString: String): WebSocketRequest =
    create(Uri.create(uriString))

  /**
   * Wraps a Scala version of WebSocketRequest.
   */
  def wrap(scalaRequest: ScalaWebSocketRequest): WebSocketRequest =
    new WebSocketRequest {
      def addHeader(header: HttpHeader): WebSocketRequest =
        transform(s ⇒ s.copy(extraHeaders = s.extraHeaders :+ header.asScala))
      def requestSubprotocol(subprotocol: String): WebSocketRequest =
        transform(_.copy(subprotocol = Some(subprotocol)))

      def asScala: ScalaWebSocketRequest = scalaRequest

      def transform(f: ScalaWebSocketRequest ⇒ ScalaWebSocketRequest): WebSocketRequest =
        wrap(f(asScala))
    }
}
