/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import java.nio.charset.Charset
import org.reactivestreams.Publisher
import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.http.model._
import akka.http.model.headers._
import akka.http.engine.rendering.RenderSupport._
import akka.http.util._
import akka.stream.impl.SynchronousPublisherFromIterable
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, Transformer }
import akka.util.ByteString
import HttpEntity._

/**
 * INTERNAL API
 */
private[http] class BodyPartRenderer(boundary: String,
                                     nioCharset: Charset,
                                     partHeadersSizeHint: Int,
                                     log: LoggingAdapter)(implicit fm: FlowMaterializer) extends Transformer[BodyPart, Publisher[ChunkStreamPart]] {

  private[this] var firstBoundaryRendered = false

  def onNext(bodyPart: BodyPart): List[Publisher[ChunkStreamPart]] = {
    val r = new CustomCharsetByteStringRendering(nioCharset, partHeadersSizeHint)

    def renderBoundary(): Unit = {
      if (firstBoundaryRendered) r ~~ CrLf
      r ~~ '-' ~~ '-' ~~ boundary ~~ CrLf
    }

    def render(h: HttpHeader) = r ~~ h ~~ CrLf

    @tailrec def renderHeaders(remaining: List[HttpHeader]): Unit =
      remaining match {
        case head :: tail ⇒ head match {
          case x: `Content-Length` ⇒
            suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")
            renderHeaders(tail)

          case x: `Content-Type` ⇒
            suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpRequest.entity.contentType` instead.")
            renderHeaders(tail)

          case x: RawHeader if (x is "content-type") || (x is "content-length") ⇒
            suppressionWarning(log, x, "illegal RawHeader")
            renderHeaders(tail)

          case x ⇒
            render(x)
            renderHeaders(tail)
        }
        case Nil ⇒ r ~~ CrLf
      }

    def bodyPartChunks(data: Publisher[ByteString]): List[Publisher[ChunkStreamPart]] = {
      val entityChunks = Flow(data).map[ChunkStreamPart](Chunk(_)).toPublisher()
      Flow[ChunkStreamPart](Chunk(r.get) :: Nil).concat(entityChunks).toPublisher() :: Nil
    }

    def completePartRendering(): List[Publisher[ChunkStreamPart]] =
      bodyPart.entity match {
        case x if x.isKnownEmpty     ⇒ chunkStream(r.get)
        case Strict(_, data)         ⇒ chunkStream((r ~~ data).get)
        case Default(_, _, data)     ⇒ bodyPartChunks(data)
        case CloseDelimited(_, data) ⇒ bodyPartChunks(data)
        case Chunked(_, chunks) ⇒
          val entityChunks = Flow(chunks).filter(!_.isLastChunk).toPublisher()
          Flow(Chunk(r.get) :: Nil).concat(entityChunks).toPublisher() :: Nil
      }

    renderBoundary()
    firstBoundaryRendered = true
    renderEntityContentType(r, bodyPart.entity)
    renderHeaders(bodyPart.headers.toList)
    completePartRendering()
  }

  override def onTermination(e: Option[Throwable]): List[Publisher[ChunkStreamPart]] =
    if (e.isEmpty && firstBoundaryRendered) {
      val r = new ByteStringRendering(boundary.length + 4)
      r ~~ CrLf ~~ '-' ~~ '-' ~~ boundary ~~ '-' ~~ '-'
      chunkStream(r.get)
    } else Nil

  private def chunkStream(byteString: ByteString) =
    SynchronousPublisherFromIterable[ChunkStreamPart](Chunk(byteString) :: Nil) :: Nil
}

