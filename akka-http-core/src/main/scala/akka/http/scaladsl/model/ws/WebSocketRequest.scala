/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import scala.language.implicitConversions

import scala.collection.immutable

import akka.http.scaladsl.model.{ HttpHeader, Uri }

/**
 * Represents a WebSocket request.
 * @param uri The target URI to connect to.
 * @param extraHeaders Extra headers to add to the WebSocket request.
 * @param subprotocol A WebSocket subprotocol if required.
 */
final case class WebSocketRequest(
  uri:          Uri,
  extraHeaders: immutable.Seq[HttpHeader] = Nil,
  subprotocol:  Option[String]            = None)
object WebSocketRequest {
  implicit def fromTargetUri(uri: Uri): WebSocketRequest = WebSocketRequest(uri)
  implicit def fromTargetUriString(uriString: String): WebSocketRequest = WebSocketRequest(uriString)
}