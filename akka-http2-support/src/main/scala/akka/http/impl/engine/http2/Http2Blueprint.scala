/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.http2.parsing.HttpRequestHeaderHpackDecompression
import akka.http.impl.engine.http2.rendering.HttpResponseHeaderHpackCompression
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString

/** Represents one direction of an Http2 substream */
final case class Http2SubStream(initialFrame: StreamFrameEvent, frames: Source[StreamFrameEvent, _]) {
  def streamId: Int = initialFrame.streamId
}

object Http2Blueprint {
  def serverStack(): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
    httpLayer() atop
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
  def demux(): BidiFlow[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream, NotUsed] =
    BidiFlow.fromGraph(new Http2ServerDemux)

  /**
   * Translation between substream frames and Http messages (both directions)
   *
   * To make use of parallelism requests and responses need to be associated (other than by ordering), suggestion
   * is to add a special (virtual) header containing the streamId (or any other kind of token) is added to the HttRequest
   * that must be reproduced in an HttpResponse. This can be done automatically for the bindAndHandleAsync API but for
   * bindAndHandle the user needs to take of this manually.
   */
  def httpLayer(): BidiFlow[HttpResponse, Http2SubStream, Http2SubStream, HttpRequest, NotUsed] = {
    val incomingRequests = Flow[Http2SubStream].via(new HttpRequestHeaderHpackDecompression)
    val outgoingResponses = Flow[HttpResponse].via(new HttpResponseHeaderHpackCompression)
    BidiFlow.fromFlows(outgoingResponses, incomingRequests)
  }
}
