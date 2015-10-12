/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.ws

import scala.language.implicitConversions

import scala.collection.immutable

import akka.http.scaladsl.model.{ HttpHeader, Uri }

/**
 * Represents a Websocket request.
 * @param uri The target URI to connect to.
 * @param extraHeaders Extra headers to add to the Websocket request.
 * @param subprotocol A Websocket subprotocol if required.
 */
final case class WebsocketRequest(
  uri: Uri,
  extraHeaders: immutable.Seq[HttpHeader] = Nil,
  subprotocol: Option[String] = None)
object WebsocketRequest {
  implicit def fromTargetUri(uri: Uri): WebsocketRequest = WebsocketRequest(uri)
  implicit def fromTargetUriString(uriString: String): WebsocketRequest = WebsocketRequest(uriString)
}