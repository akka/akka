/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.stream.{ Graph, FlowShape }

/**
 * Currently internal API to handle FrameEvents directly.
 *
 * INTERNAL API
 */
private[http] abstract class UpgradeToWebSocketLowLevel extends InternalCustomHeader("UpgradeToWebSocket") with UpgradeToWebSocket {
  /**
   * The low-level interface to create WebSocket server based on "frames".
   * The user needs to handle control frames manually in this case.
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to WebSocket and
   * use the supplied handler to handle incoming WebSocket frames.
   *
   * INTERNAL API (for now)
   */
  private[http] def handleFrames(handlerFlow: Graph[FlowShape[FrameEvent, FrameEvent], Any], subprotocol: Option[String] = None): HttpResponse
}
