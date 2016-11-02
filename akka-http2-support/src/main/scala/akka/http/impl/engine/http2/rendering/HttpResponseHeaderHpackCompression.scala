/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.rendering

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.{ ByteBuffer, ByteOrder }

import akka.http.impl.engine.http2.DataFrame
import akka.http.impl.engine.http2.{ HeadersFrame, Http2Protocol, Http2SubStream }
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString

/** INTERNAL API */
final class HttpResponseHeaderHpackCompression extends GraphStage[FlowShape[HttpResponse, Http2SubStream]] {

  import HttpResponseHeaderHpackCompression._

  // FIXME Make configurable
  final val maxHeaderTableSize = 4096

  val in = Inlet[HttpResponse]("HeaderDecompression.in")
  val out = Outlet[Http2SubStream]("HeaderDecompression.out")

  override def shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    val encoder = new com.twitter.hpack.Encoder(maxHeaderTableSize)
    val os = new ByteArrayOutputStream() // FIXME: use a reasonable default size

    override def onPush(): Unit = {
      val response = grab(in)
      // TODO possibly specialize static table? https://http2.github.io/http2-spec/compression.html#static.table.definition
      val headerBlockFragment = encodeResponse(response)

      def failBecauseOfMissingHeader: Nothing =
        // header is missing, shutting down because we will most likely otherwise miss a response and leak a substream
        // TODO: optionally a less drastic measure would be only resetting all the active substreams
        throw new RuntimeException("Received response for HTTP/2 request without Http2StreamIdHeader. Failing connection.")

      val streamId = response.header[Http2StreamIdHeader].getOrElse(failBecauseOfMissingHeader).streamId

      val dataFrames =
        if (response.entity.isKnownEmpty) Source.empty
        else
          response.entity.dataBytes.map(bytes ⇒ DataFrame(streamId, endStream = false, bytes)) ++
            Source.single(DataFrame(streamId, endStream = true, ByteString.empty))

      val headers = HeadersFrame(streamId, endStream = dataFrames == Source.empty, endHeaders = true, headerBlockFragment)
      val http2SubStream = Http2SubStream(headers, dataFrames)
      push(out, http2SubStream)
    }

    def encodeResponse(response: HttpResponse): ByteString = {
      encoder.encodeHeader(os, StatusKey, response.status.intValue.toString.getBytes, false) // TODO so wasteful
      response.headers
        .filter(_.renderInResponses)
        .foreach { h ⇒
          val nameBytes = h.lowercaseName.getBytes
          val valueBytes = h.value.getBytes
          encoder.encodeHeader(os, nameBytes, valueBytes, false)
        }

      val res = ByteString(os.toByteArray)
      os.reset()
      res
    }

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

}

object HttpResponseHeaderHpackCompression {
  final val StatusKey = ":status".getBytes
}
