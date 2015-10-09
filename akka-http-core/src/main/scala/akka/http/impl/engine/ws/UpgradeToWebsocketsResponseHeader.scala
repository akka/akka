/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.headers.CustomHeader
import akka.http.scaladsl.model.ws.Message
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

private[http] final case class UpgradeToWebsocketResponseHeader(handler: Either[Flow[FrameEvent, FrameEvent, Any], Flow[Message, Message, Any]])
  extends InternalCustomHeader("UpgradeToWebsocketResponseHeader")

private[http] abstract class InternalCustomHeader(val name: String) extends CustomHeader {
  override def suppressRendering: Boolean = true

  def value(): String = ""
}