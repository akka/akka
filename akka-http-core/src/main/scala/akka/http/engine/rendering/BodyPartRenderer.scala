/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import java.nio.charset.Charset
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.http.model._
import akka.http.model.headers._
import akka.http.engine.rendering.RenderSupport._
import akka.http.util._
import akka.stream.scaladsl.Source
import akka.stream.Transformer
import akka.util.ByteString
import HttpEntity._

/**
 * INTERNAL API
 */
private[http] object BodyPartRenderer {

  def streamed(boundary: String,
               nioCharset: Charset,
               partHeadersSizeHint: Int,
               log: LoggingAdapter): Transformer[Multipart.BodyPart, Source[ChunkStreamPart]] =
    new Transformer[Multipart.BodyPart, Source[ChunkStreamPart]] {
      var firstBoundaryRendered = false

      def onNext(bodyPart: Multipart.BodyPart): List[Source[ChunkStreamPart]] = {
        val r = new CustomCharsetByteStringRendering(nioCharset, partHeadersSizeHint)

        def bodyPartChunks(data: Source[ByteString]): List[Source[ChunkStreamPart]] = {
          val entityChunks = data.map[ChunkStreamPart](Chunk(_))
          (Source(Chunk(r.get) :: Nil) ++ entityChunks) :: Nil
        }

        def completePartRendering(): List[Source[ChunkStreamPart]] =
          bodyPart.entity match {
            case x if x.isKnownEmpty       ⇒ chunkStream(r.get)
            case Strict(_, data)           ⇒ chunkStream((r ~~ data).get)
            case Default(_, _, data)       ⇒ bodyPartChunks(data)
            case IndefiniteLength(_, data) ⇒ bodyPartChunks(data)
          }

        renderBoundary(r, boundary, suppressInitialCrLf = !firstBoundaryRendered)
        firstBoundaryRendered = true
        renderEntityContentType(r, bodyPart.entity)
        renderHeaders(r, bodyPart.headers, log)
        completePartRendering()
      }

      override def onTermination(e: Option[Throwable]): List[Source[ChunkStreamPart]] =
        if (e.isEmpty && firstBoundaryRendered) {
          val r = new ByteStringRendering(boundary.length + 4)
          renderFinalBoundary(r, boundary)
          chunkStream(r.get)
        } else Nil

      private def chunkStream(byteString: ByteString) =
        Source[ChunkStreamPart](Chunk(byteString) :: Nil) :: Nil
    }

  def strict(parts: immutable.Seq[Multipart.BodyPart.Strict], boundary: String, nioCharset: Charset,
             partHeadersSizeHint: Int, log: LoggingAdapter): ByteString = {
    val r = new CustomCharsetByteStringRendering(nioCharset, partHeadersSizeHint)
    if (parts.nonEmpty) {
      for (part ← parts) {
        renderBoundary(r, boundary, suppressInitialCrLf = part eq parts.head)
        renderEntityContentType(r, part.entity)
        renderHeaders(r, part.headers, log)
        r ~~ part.entity.data
      }
      renderFinalBoundary(r, boundary)
    }
    r.get
  }

  private def renderBoundary(r: Rendering, boundary: String, suppressInitialCrLf: Boolean = false): Unit = {
    if (!suppressInitialCrLf) r ~~ CrLf
    r ~~ '-' ~~ '-' ~~ boundary ~~ CrLf
  }

  private def renderFinalBoundary(r: Rendering, boundary: String): Unit =
    r ~~ CrLf ~~ '-' ~~ '-' ~~ boundary ~~ '-' ~~ '-'

  private def renderHeaders(r: Rendering, headers: immutable.Seq[HttpHeader], log: LoggingAdapter): Unit = {
    headers foreach renderHeader(r, log)
    r ~~ CrLf
  }

  private def renderHeader(r: Rendering, log: LoggingAdapter): HttpHeader ⇒ Unit = {
    case x: `Content-Length` ⇒
      suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")

    case x: `Content-Type` ⇒
      suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpRequest.entity.contentType` instead.")

    case x: RawHeader if (x is "content-type") || (x is "content-length") ⇒
      suppressionWarning(log, x, "illegal RawHeader")

    case x ⇒ r ~~ x ~~ CrLf
  }
}