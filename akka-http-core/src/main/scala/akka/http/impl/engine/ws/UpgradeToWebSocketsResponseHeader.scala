/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.headers.CustomHeader
import akka.http.scaladsl.model.ws.Message
import akka.stream.{ Graph, FlowShape }

private[http] final case class UpgradeToWebSocketResponseHeader(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]])
  extends InternalCustomHeader("UpgradeToWebSocketResponseHeader")

private[http] abstract class InternalCustomHeader(val name: String) extends CustomHeader {
  final def renderInRequests = false
  final def renderInResponses = false
  def value: String = ""
}
