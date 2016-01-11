/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

import akka.http.javadsl.model.{ Uri, HttpHeader }
import akka.http.scaladsl.model.ws.{ WebsocketRequest ⇒ ScalaWebsocketRequest }

/**
 * Represents a Websocket request. Use `WebsocketRequest.create` to create a request
 * for a target URI and then use `addHeader` or `requestSubprotocol` to set optional
 * details.
 */
abstract class WebsocketRequest {
  /**
   * Return a copy of this request that contains the given additional header.
   */
  def addHeader(header: HttpHeader): WebsocketRequest

  /**
   * Return a copy of this request that will require that the server uses the
   * given Websocket subprotocol.
   */
  def requestSubprotocol(subprotocol: String): WebsocketRequest

  def asScala: ScalaWebsocketRequest
}
object WebsocketRequest {
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Creates a WebsocketRequest to a target URI. Use the methods on `WebsocketRequest`
   * to specify further details.
   */
  def create(uri: Uri): WebsocketRequest =
    wrap(ScalaWebsocketRequest(uri.asScala))

  /**
   * Creates a WebsocketRequest to a target URI. Use the methods on `WebsocketRequest`
   * to specify further details.
   */
  def create(uriString: String): WebsocketRequest =
    create(Uri.create(uriString))

  /**
   * Wraps a Scala version of WebsocketRequest.
   */
  def wrap(scalaRequest: ScalaWebsocketRequest): WebsocketRequest =
    new WebsocketRequest {
      def addHeader(header: HttpHeader): WebsocketRequest =
        transform(s ⇒ s.copy(extraHeaders = s.extraHeaders :+ header.asScala))
      def requestSubprotocol(subprotocol: String): WebsocketRequest =
        transform(_.copy(subprotocol = Some(subprotocol)))

      def asScala: ScalaWebsocketRequest = scalaRequest

      def transform(f: ScalaWebsocketRequest ⇒ ScalaWebsocketRequest): WebsocketRequest =
        wrap(f(asScala))
    }
}
