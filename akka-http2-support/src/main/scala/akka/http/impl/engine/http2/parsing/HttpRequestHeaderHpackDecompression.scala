/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.parsing

import akka.event.Logging
import akka.http.impl.engine.http2.BufferedOutletSupport
import akka.http.impl.engine.http2.{ HeadersFrame, Http2Protocol, Http2SubStream, StreamFrameEvent }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import com.twitter.hpack.HeaderListener
import HttpRequestHeaderHpackDecompression._

/** INTERNAL API */
private[http2] final class HttpRequestHeaderHpackDecompression extends GraphStage[FlowShape[Http2SubStream, HttpRequest]] {
  private final val ColonByte = ':'.toByte

  val streamIn = Inlet[Http2SubStream](Logging.simpleName(this) + ".streamIn")
  val requestOut = Outlet[HttpRequest](Logging.simpleName(this) + ".requestOut")
  override val shape = FlowShape.of(streamIn, requestOut)

  // format: OFF
  override def createLogic(inheritedAttributes: Attributes) =
    new GraphStageLogic(shape)
      with InHandler
      with HeaderListener with BufferedOutletSupport {
      // format: ON

      /** While we're pulling a SubStreams frames, we should not pass through completion */
      private var pullingSubStreamFrames = false
      /** If the outer upstream has completed while we were pulling substream frames, we should complete it after we emit the request. */
      private var completionPending = false

      val zeroRequest = HttpRequest().withProtocol(HttpProtocols.`HTTP/2.0`)
      private[this] var beingBuiltRequest: HttpRequest = zeroRequest // TODO replace with "RequestBuilder" that's more efficient

      val decoder = new com.twitter.hpack.Decoder(maxHeaderSize, maxHeaderTableSize)

      // buffer outgoing requests if necessary (total number limited by SETTINGS_MAX_CONCURRENT_STREAMS)
      val bufferedRequestOut = new BufferedOutlet(requestOut)

      setHandler(streamIn, this)
      override def preStart(): Unit = pull(streamIn)

      override def onPush(): Unit = {
        val httpSubStream = grab(streamIn)
        // no backpressure (limited by SETTINGS_MAX_CONCURRENT_STREAMS)
        pull(streamIn)

        def hasEndStream(frame: StreamFrameEvent): Boolean = frame match {
          case h: HeadersFrame ⇒ h.endStream
          case _               ⇒ false
        }

        // FIXME: ensure that this works for all combinations of events
        //   (e.g. there can be an initial frame with endStream = true, endHeaders = false
        //    in which case we need to wait for a continuation, etc.)
        if (hasEndStream(httpSubStream.initialFrame)) {
          val pushedRequest = processFrame(httpSubStream, httpSubStream.initialFrame)

          // we know frames should be empty here.
          // but for sanity lets kill that stream anyway I guess (at least for now)
          killFramesStream(httpSubStream)
        } else {
          processFrame(httpSubStream, httpSubStream.initialFrame)
          // more data in following frames

          pullingSubStreamFrames = true
          completionPending = false
          processRemainingFrames(httpSubStream)
        }
      }

      // this is invoked synchronously from decoder.decode()
      override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
        val nameString = new String(name) // FIXME wasteful :-(
        val valueString = new String(value)

        // FIXME lookup here must be optimised
        if (name.head == ColonByte) {
          nameString match {
            case ":method" ⇒
              val method = HttpMethods.getForKey(valueString)
                .getOrElse(throw new IllegalArgumentException(s"Unknown HttpMethod! Was: '$valueString'."))

              // FIXME only copy if value has changed to avoid churning allocs
              beingBuiltRequest = beingBuiltRequest.copy(method = method)

            case ":path" ⇒
              // FIXME only copy if value has changed to avoid churning allocs
              beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withPath(Uri.Path(valueString)))

            case ":authority" ⇒
              beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withAuthority(Uri.Authority.parse(valueString)))

            case ":scheme" ⇒
              beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withScheme(valueString))

            // TODO handle all special headers

            case unknown ⇒
              throw new Exception(s": prefixed header should be emitted well-typed! Was: '${new String(unknown)}'. This is a bug.")
          }
        } else
          // TODO handle all typed headers
          beingBuiltRequest = beingBuiltRequest.addHeader(RawHeader(nameString, new String(value)))
      }

      override def onUpstreamFinish(): Unit = {
        if (pullingSubStreamFrames) {
          // we're currently pulling Frames out of the SubStream, thus we should not shut-down just yet
          completionPending = true // TODO I think we don't need this, can just rely on isClosed(in)?
        } else {
          // we've emitted all there was to emit, and can complete this stage
          completeStage()
        }
      }

      private def processFrame(http2SubStream: Http2SubStream, frame: StreamFrameEvent): Boolean = frame match {
        case h: HeadersFrame ⇒
          val is = ByteStringInputStream(h.headerBlockFragment)

          beingBuiltRequest = zeroRequest.addHeader(Http2StreamIdHeader(h.streamId))
          decoder.decode(is, this) // this: HeaderListener (invoked synchronously)
          if (h.endHeaders) decoder.endHeaderBlock()

          pushIfReady(h)
        case _ ⇒
          throw new UnsupportedOperationException(s"Not implemented to handle $frame! TODO / FIXME for impl.")
      }

      private def processRemainingFrames(http2SubStream: Http2SubStream): Unit = {
        val subIn = new SubSinkInlet[StreamFrameEvent]("frames.in(for:Http2SubStream)")
        subIn.setHandler(new InHandler {
          override def onPush(): Unit = {
            val frame = subIn.grab()
            val pushedResponse = processFrame(http2SubStream, frame)
            if (pushedResponse) {
              // FIXME but we need to keep pulling it until completion, as it may contain DataFrames

              // FIXME then finally we can pull the outer stream again, which gives us a new substream to work on
              pull(streamIn) // pull outer stream, we're ready for new SubStream
            } else {
              // still more data to read from the SubSource before we can start emitting the HttpResponse (e.g. more headers)
              subIn.pull()
            }
          }
        })
        subIn.pull()
        http2SubStream.frames.runWith(subIn.sink)(interpreter.subFusingMaterializer)
      }

      /** Returns `true` if it emitted a complete [[HttpRequest]], and `false` otherwise */
      private def pushIfReady(headersFrame: HeadersFrame): Boolean = {
        if (headersFrame.endHeaders) {
          bufferedRequestOut.push(beingBuiltRequest)
          beingBuiltRequest = zeroRequest
          true
        } else {
          // else we're awaiting a CONTINUATION frame with the remaining headers
          false
        }
      }

      // TODO if we can inspect it and it's really empty we don't need to materialize, for safety otherwise we cancel that stream
      private def killFramesStream(httpSubStream: Http2SubStream) = {
        if (httpSubStream.frames == Source.empty) ()
        else {
          // it should be empty, but wasn't, let's kill it
          // FIXME probably this should be removed and we should just fail?
          interpreter.log.warning("Expected no more frames, but source was NOT empty! " +
            "Draining the remaining frames, from: {}", httpSubStream.frames)
          httpSubStream.frames.runWith(Sink.cancelled)(interpreter.materializer)
        }
      }
    }

}

/** INTERNAL API */
private[http2] object HttpRequestHeaderHpackDecompression {
  final val maxHeaderSize = 4096
  final val maxHeaderTableSize = 4096
}