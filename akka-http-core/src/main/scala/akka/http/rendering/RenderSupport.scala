/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import org.reactivestreams.api.Producer
import scala.collection.immutable
import akka.parboiled2.CharUtils
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.impl.SynchronousProducerFromIterable
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, Transformer }
import akka.http.model._
import akka.http.util._

/**
 * INTERNAL API
 */
private object RenderSupport {
  val DefaultStatusLine = "HTTP/1.1 200 OK\r\n".getAsciiBytes
  val StatusLineStart = "HTTP/1.1 ".getAsciiBytes
  val Chunked = "chunked".getAsciiBytes
  val KeepAlive = "Keep-Alive".getAsciiBytes
  val Close = "close".getAsciiBytes

  def CrLf = Rendering.CrLf

  implicit val trailerRenderer = Renderer.genericSeqRenderer[Renderable, HttpHeader](CrLf, Rendering.Empty)

  val defaultLastChunkBytes: ByteString = renderChunk(HttpEntity.LastChunk)

  def renderEntityContentType(r: Rendering, entity: HttpEntity): Unit =
    if (entity.contentType != ContentTypes.NoContentType)
      r ~~ headers.`Content-Type` ~~ entity.contentType ~~ CrLf

  def renderByteStrings(r: ByteStringRendering, entityBytes: ⇒ Producer[ByteString], materializer: FlowMaterializer,
                        skipEntity: Boolean = false): immutable.Seq[Producer[ByteString]] = {
    val messageStart = SynchronousProducerFromIterable(r.get :: Nil)
    val messageBytes =
      if (!skipEntity) Flow(messageStart).concat(entityBytes).toProducer(materializer)
      else messageStart
    messageBytes :: Nil
  }

  class ChunkTransformer extends Transformer[HttpEntity.ChunkStreamPart, ByteString] {
    var lastChunkSeen = false
    def onNext(chunk: HttpEntity.ChunkStreamPart): immutable.Seq[ByteString] = {
      if (chunk.isLastChunk) lastChunkSeen = true
      renderChunk(chunk) :: Nil
    }
    override def isComplete = lastChunkSeen
    override def onTermination(e: Option[Throwable]) = if (lastChunkSeen) Nil else defaultLastChunkBytes :: Nil
  }

  class CheckContentLengthTransformer(length: Long) extends Transformer[ByteString, ByteString] {
    var sent = 0L
    def onNext(elem: ByteString): immutable.Seq[ByteString] = {
      sent += elem.length
      if (sent > length)
        throw new InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity chunk stream amounts to more bytes")
      elem :: Nil
    }

    override def onTermination(e: Option[Throwable]): immutable.Seq[ByteString] = {
      if (sent < length)
        throw new InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity chunk stream amounts to ${length - sent} bytes less")
      Nil
    }
  }

  private def renderChunk(chunk: HttpEntity.ChunkStreamPart): ByteString = {
    import chunk._
    val renderedSize = // buffer space required for rendering (without trailer)
      CharUtils.numberOfHexDigits(data.length) +
        (if (extension.isEmpty) 0 else extension.length + 1) +
        data.length +
        2 + 2
    val r = new ByteStringRendering(renderedSize)
    r ~~% data.length
    if (extension.nonEmpty) r ~~ ';' ~~ extension
    r ~~ CrLf
    chunk match {
      case HttpEntity.Chunk(data, _)        ⇒ r ~~ data
      case HttpEntity.LastChunk(_, Nil)     ⇒ // nothing to do
      case HttpEntity.LastChunk(_, trailer) ⇒ r ~~ trailer ~~ CrLf
    }
    r ~~ CrLf
    r.get
  }

  def suppressionWarning(log: LoggingAdapter, h: HttpHeader,
                         msg: String = "the akka-http-core layer sets this header automatically!"): Unit =
    log.warning("Explicitly set HTTP header '{}' is ignored, {}", h, msg)
}
