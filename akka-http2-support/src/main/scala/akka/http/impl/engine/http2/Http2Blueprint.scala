/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.event.Logging
import akka.http.impl.engine.http2.parsing.HttpRequestHeaderHpackDecompression
import akka.http.impl.engine.http2.rendering.HttpResponseHeaderHpackCompression
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
 * Represents one direction of an Http2 substream.
 *
 * Note that the [[Http2ServerDemux]] takes care of assembling multiple HEADER + CONTINUATION frames together,
 * and passes the combined frame as `initialFrame` here. Following frames MUST be data frames (as per spec) -
 * and are directly related to the [[akka.http.scaladsl.model.HttpEntity]] offered by this stream.
 */
private[http2] final case class Http2SubStream(initialFrame: HeadersFrame, frames: Source[DataFrame, _]) {
  def streamId: Int = initialFrame.streamId
}

object Http2Blueprint {
  // format: OFF
  def serverStack(): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] = {
    httpLayer() atop
    demux() atop
    framing()
  }
  // format: ON

  def framing(): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].map(FrameRenderer.render),
      Flow[ByteString].via(new FrameParser(shouldReadPreface = true)))

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
      .log(Logging.simpleName(getClass)) //FIXME replace with a proper `atop logging()`
    val outgoingResponses = Flow[HttpResponse].via(new HttpResponseHeaderHpackCompression)
    BidiFlow.fromFlows(outgoingResponses, incomingRequests)
  }

  /**
   * Returns a flow that handles `parallelism` requests in parallel, automatically keeping track of the
   * Http2StreamIdHeader between request and responses.
   */
  def handleWithStreamIdHeader(parallelism: Int)(handler: HttpRequest ⇒ Future[HttpResponse])(implicit ec: ExecutionContext): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest]
      .mapAsyncUnordered(parallelism) { req ⇒
        val response = handler(req)

        req.header[Http2StreamIdHeader] match {
          case Some(streamIdHeader) ⇒ response.map(_.addHeader(streamIdHeader)) // add stream id header when request had it
          case None                 ⇒ response
        }
      }
}
