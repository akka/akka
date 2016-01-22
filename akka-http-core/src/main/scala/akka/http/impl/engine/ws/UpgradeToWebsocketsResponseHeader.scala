/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.headers.CustomHeader
import akka.http.scaladsl.model.ws.Message
import akka.stream.{ Graph, FlowShape }

private[http] final case class UpgradeToWebsocketResponseHeader(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]])
  extends InternalCustomHeader("UpgradeToWebsocketResponseHeader")

private[http] abstract class InternalCustomHeader(val name: String) extends CustomHeader {
  final def renderInRequests = false
  final def renderInResponses = false
  def value: String = ""
}
