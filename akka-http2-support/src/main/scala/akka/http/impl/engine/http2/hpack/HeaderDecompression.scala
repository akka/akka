/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.hpack

import java.io.IOException
import java.nio.charset.{ Charset, StandardCharsets }

import akka.http.impl.engine.http2.Http2Protocol.{ ErrorCode, SettingIdentifier }
import akka.http.impl.engine.http2._
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.util.ByteString
import com.twitter.hpack.HeaderListener

import scala.collection.immutable.VectorBuilder

/**
 * INTERNAL API
 *
 * Can be used on server and client side.
 */
private[http2] object HeaderDecompression extends GraphStage[FlowShape[FrameEvent, FrameEvent]] {
  val UTF8 = StandardCharsets.UTF_8

  val eventsIn = Inlet[FrameEvent]("HeaderDecompression.eventsIn")
  val eventsOut = Outlet[FrameEvent]("HeaderDecompression.eventsOut")

  val shape = FlowShape(eventsIn, eventsOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new HandleOrPassOnStage[FrameEvent, FrameEvent](shape) {
    val decoder = new com.twitter.hpack.Decoder(Http2Protocol.InitialMaxHeaderListSize, Http2Protocol.InitialMaxHeaderTableSize)

    become(Idle)

    // simple state machine
    // Idle: no ongoing HEADERS parsing
    // Receiving headers: waiting for CONTINUATION frame

    def parseAndEmit(streamId: Int, endStream: Boolean, payload: ByteString, prioInfo: Option[PriorityFrame]): Unit = {
      var headers = new VectorBuilder[(String, String)]
      object Receiver extends HeaderListener {
        def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit =
          // TODO: optimization: use preallocated strings for well-known names, similar to what happens in HeaderParser
          headers += new String(name, UTF8) → new String(value, UTF8)
      }
      try {
        decoder.decode(ByteStringInputStream(payload), Receiver)
        decoder.endHeaderBlock() // TODO: do we have to check the result here?

        push(eventsOut, ParsedHeadersFrame(streamId, endStream, headers.result(), prioInfo))
      } catch {
        case ex: IOException ⇒
          // this is signalled by the decoder when it failed, we want to react to this by rendering a GOAWAY frame
          fail(eventsOut, new Http2Compliance.Http2ProtocolException(ErrorCode.COMPRESSION_ERROR, "Decompression failed."))
      }
    }

    object Idle extends State {
      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case HeadersFrame(streamId, endStream, endHeaders, fragment, prioInfo) ⇒
          if (endHeaders) parseAndEmit(streamId, endStream, fragment, prioInfo)
          else {
            become(new ReceivingHeaders(streamId, endStream, fragment, prioInfo))
            pull(eventsIn)
          }
        case c: ContinuationFrame ⇒
          protocolError(s"Received unexpected continuation frame: $c")

        // FIXME: handle SETTINGS frames that change decompression parameters
      }
    }
    class ReceivingHeaders(streamId: Int, endStream: Boolean, initiallyReceivedData: ByteString, priorityInfo: Option[PriorityFrame]) extends State {
      var receivedData = initiallyReceivedData

      val handleEvent: PartialFunction[FrameEvent, Unit] = {
        case ContinuationFrame(`streamId`, endHeaders, payload) ⇒
          if (endHeaders) {
            parseAndEmit(streamId, endStream, receivedData ++ payload, priorityInfo)
            become(Idle)
          } else receivedData ++= payload
        case x ⇒ protocolError(s"While waiting for CONTINUATION frame on stream $streamId received unexpected frame $x")
      }
    }

    def protocolError(msg: String): Unit = failStage(new RuntimeException(msg)) // TODO: replace with right exception type
  }
}
