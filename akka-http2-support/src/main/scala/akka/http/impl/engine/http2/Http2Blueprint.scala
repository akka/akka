/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.util.ByteString

object Http2Blueprint {
  def serverStack(): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
    demux() atop
      flowControl() atop
      framing()

  def framing(): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].map(FrameRenderer.render),
      Flow[ByteString].via(new FrameParser(shouldReadPreface = true)))

  /** Manages flow control for streams */
  def flowControl(): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.identity

  /**
   * Creates substreams for every stream and manages stream state machines
   * and handles priorization (TODO: later)
   */
  def demux(): BidiFlow[HttpResponse, FrameEvent, FrameEvent, HttpRequest, NotUsed] =
    BidiFlow.fromFlows(Flow[HttpResponse].map(_ ⇒ DataFrame(-1, true, ByteString())), Flow[FrameEvent].map(_ ⇒ HttpRequest()))

  /** A handler for a single server stream */
  def serverStreamHandler(): BidiFlow[HttpResponse, FrameEvent, FrameEvent, HttpRequest, NotUsed] = ???
}
