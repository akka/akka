/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.framing.{ Http2FrameParsing, Http2FrameRendering }
import akka.http.impl.engine.http2.hpack.{ HeaderCompression, HeaderDecompression }
import akka.http.impl.engine.parsing.HttpHeaderParser
import akka.http.impl.engine.server.HttpAttributes
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.http.scaladsl.settings.{ ParserSettings, ServerSettings }
import akka.stream.scaladsl.{ BidiFlow, Flow, Source }
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Represents one direction of an Http2 substream.
 */
private[http2] final case class Http2SubStream(
  initialHeaders: ParsedHeadersFrame,
  data:           Source[ByteString, Any]) {
  def streamId: Int = initialHeaders.streamId
}

object Http2Blueprint {
  
  // format: OFF
  def serverStack(settings: ServerSettings, log: LoggingAdapter): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
    httpLayer(settings, log) atop
      demux() atop
      // FrameLogger.bidi atop // enable for debugging
      hpackCoding() atop
      // LogByteStringTools.logToStringBidi("framing") atop // enable for debugging
      framing()
  // format: ON

  def framing(): BidiFlow[FrameEvent, ByteString, ByteString, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].via(new Http2FrameRendering),
      Flow[ByteString].via(new Http2FrameParsing(shouldReadPreface = true)))

  /**
   * Runs hpack encoding and decoding. Incoming frames that are processed are HEADERS and CONTINUATION.
   * Outgoing frame is ParsedHeadersFrame.
   * Other frames are propagated unchanged.
   *
   * TODO: introduce another FrameEvent type that exclude HeadersFrame and ContinuationFrame from
   * reaching the higher-level.
   */
  def hpackCoding(): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].via(HeaderCompression),
      Flow[FrameEvent].via(HeaderDecompression)
    )

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
  def httpLayer(settings: ServerSettings, log: LoggingAdapter): BidiFlow[HttpResponse, Http2SubStream, Http2SubStream, HttpRequest, NotUsed] = {
    val parserSettings = settings.parserSettings
    // This is master header parser, every other usage should do .createShallowCopy()
    // HttpHeaderParser is not thread safe and should not be called concurrently,
    // the internal trie, however, has built-in protection and will do copy-on-write
    val masterHttpHeaderParser = HttpHeaderParser(parserSettings, log) { info ⇒
      if (parserSettings.illegalHeaderWarnings)
        logParsingError(info withSummaryPrepended "Illegal request header", log, parserSettings.errorLoggingVerbosity)
    }
    BidiFlow.fromFlows(
      Flow[HttpResponse].map(ResponseRendering.renderResponse(settings, log)),
      Flow[Http2SubStream].via(StreamUtils.statefulAttrsMap { attrs ⇒
        val headerParser = masterHttpHeaderParser.createShallowCopy()
        RequestParsing.parseRequest(headerParser, settings, attrs)
      }))
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

  private[http2] def logParsingError(info: ErrorInfo, log: LoggingAdapter,
                                     setting: ParserSettings.ErrorLoggingVerbosity): Unit =
    setting match {
      case ParserSettings.ErrorLoggingVerbosity.Off    ⇒ // nothing to do
      case ParserSettings.ErrorLoggingVerbosity.Simple ⇒ log.warning(info.summary)
      case ParserSettings.ErrorLoggingVerbosity.Full   ⇒ log.warning(info.formatPretty)
    }
}
