/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.rendering

import java.io.OutputStream
import java.nio.{ ByteBuffer, ByteOrder }

import akka.http.impl.engine.http2.{ HeadersFrame, Http2Protocol, Http2SubStream }
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpResponse
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString

/** INTERNAL API */
final class HttpResponseHeaderHpackCompression extends GraphStage[FlowShape[HttpResponse, Http2SubStream]] {

  import HttpResponseHeaderHpackCompression._

  // FIXME Make configurable
  final val maxHeaderSize = 4096
  final val maxHeaderTableSize = 4096
  //  final val useIndexing = false
  //  final val forceHuffmanOn = false
  //  final val forceHuffmanOff = false

  val in = Inlet[HttpResponse]("HeaderDecompression.in")
  val out = Outlet[Http2SubStream]("HeaderDecompression.out")

  override def shape = FlowShape.of(in, out)

  // format: OFF
  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape)
    with InHandler with OutHandler {
    // format: ON

    val buf = {
      val b = ByteBuffer.allocate(4 * maxHeaderSize) // FIXME, just guessed a number here
      b.order(ByteOrder.LITTLE_ENDIAN)
      b
    }

    // receives bytes to be written from encoder
    private val os = new OutputStream {
      override def write(b: Int): Unit =
        buf.put(b.toByte)
      override def write(b: Array[Byte], off: Int, len: Int): Unit =
        buf.put(b, off, len)
    }

    private val encoder = new com.twitter.hpack.Encoder(maxHeaderTableSize)

    override def onPush(): Unit = {
      val response = grab(in)
      // TODO possibly specialize static table? https://http2.github.io/http2-spec/compression.html#static.table.definition
      // feed `buf` with compressed header data
      val headerBlockFragment = encodeAllHeaders(response)

      // FIXME: In order to render a REAL HeadersFrame we need to know the streamId and more info like that
      val streamId = 0 // FIXME we need to know the streamId here

      // FIXME, no idea if END_STREAM too - probably not decided by this stage?
      // TODO in our simple impl we always render all into one frame, but we may need to support rendering continuations
      val flags = Http2Protocol.Flags.END_HEADERS

      val headers = HeadersFrame(flags, streamId, headerBlockFragment)
      val http2SubStream = Http2SubStream(headers, Source.empty)
      push(out, http2SubStream)
    }

    def encodeAllHeaders(response: HttpResponse): ByteString = {
      encoder.encodeHeader(os, StatusKey, response.status.intValue.toString.getBytes, false) // TODO so wasteful
      response.headers foreach { h ⇒
        // TODO so wasteful... (it needs to be lower-cased since it's checking by == in the LUT)
        val nameBytes = h.name.toRootLowerCase.getBytes
        val valueBytes = h.value.getBytes
        encoder.encodeHeader(os, nameBytes, valueBytes, false)
      }

      // copy buffer to ByteString
      mkByteString(buf)
    }

    override def onPull(): Unit =
      pull(in)

    def mkByteString(buf: ByteBuffer): ByteString = {
      buf.flip()
      val headerBlockFragment = ByteString(buf)
      buf.flip().limit(buf.capacity())
      buf.limit
      headerBlockFragment
    }

    setHandlers(in, out, this)
  }

}

object HttpResponseHeaderHpackCompression {
  final val AuthorityKey = ":authority".getBytes
  final val StatusKey = ":status".getBytes
  final val MethofKey = ":method".getBytes

}
