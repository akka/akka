/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import org.reactivestreams.{ Subscription, Subscriber, Publisher }
import akka.parboiled2.CharUtils
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.impl.SynchronousPublisherFromIterable
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, Transformer }
import akka.http.model._
import akka.http.util._

/**
 * INTERNAL API
 */
private object RenderSupport {
  val DefaultStatusLineBytes = "HTTP/1.1 200 OK\r\n".asciiBytes
  val StatusLineStartBytes = "HTTP/1.1 ".asciiBytes
  val ChunkedBytes = "chunked".asciiBytes
  val KeepAliveBytes = "Keep-Alive".asciiBytes
  val CloseBytes = "close".asciiBytes

  def CrLf = Rendering.CrLf

  implicit val trailerRenderer = Renderer.genericSeqRenderer[Renderable, HttpHeader](CrLf, Rendering.Empty)

  val defaultLastChunkBytes: ByteString = renderChunk(HttpEntity.LastChunk)

  def renderEntityContentType(r: Rendering, entity: HttpEntity): Unit =
    if (entity.contentType != ContentTypes.NoContentType)
      r ~~ headers.`Content-Type` ~~ entity.contentType ~~ CrLf

  def renderByteStrings(r: ByteStringRendering, entityBytes: ⇒ Publisher[ByteString],
                        skipEntity: Boolean = false)(implicit fm: FlowMaterializer): List[Publisher[ByteString]] = {
    val messageStart = SynchronousPublisherFromIterable(r.get :: Nil)
    val messageBytes =
      if (!skipEntity) Flow(messageStart).concat(entityBytes).toPublisher()
      else {
        // FIXME: This should be fixed by a CancelledDrain once #15903 is done. Currently this is needed for the tests
        entityBytes.subscribe(cancelledSusbcriber)
        messageStart
      }
    messageBytes :: Nil
  }

  class ChunkTransformer extends Transformer[HttpEntity.ChunkStreamPart, ByteString] {
    var lastChunkSeen = false
    def onNext(chunk: HttpEntity.ChunkStreamPart): List[ByteString] = {
      if (chunk.isLastChunk) lastChunkSeen = true
      renderChunk(chunk) :: Nil
    }
    override def isComplete = lastChunkSeen
    override def onTermination(e: Option[Throwable]) = if (lastChunkSeen) Nil else defaultLastChunkBytes :: Nil
  }

  class CheckContentLengthTransformer(length: Long) extends Transformer[ByteString, ByteString] {
    var sent = 0L
    def onNext(elem: ByteString): List[ByteString] = {
      sent += elem.length
      if (sent > length)
        throw new InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity chunk stream amounts to more bytes")
      elem :: Nil
    }

    override def onTermination(e: Option[Throwable]): List[ByteString] = {
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
