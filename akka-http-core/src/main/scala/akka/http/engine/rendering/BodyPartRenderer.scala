/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import java.nio.charset.Charset
import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.http.model._
import akka.http.model.headers._
import akka.http.engine.rendering.RenderSupport._
import akka.http.util._
import akka.stream.scaladsl2._
import akka.stream.Transformer
import akka.util.ByteString
import HttpEntity._

/**
 * INTERNAL API
 */
private[http] class BodyPartRenderer(boundary: String,
                                     nioCharset: Charset,
                                     partHeadersSizeHint: Int,
                                     log: LoggingAdapter)(implicit fm: FlowMaterializer) extends Transformer[BodyPart, FlowWithSource[_, ChunkStreamPart]] {

  private[this] var firstBoundaryRendered = false

  def onNext(bodyPart: BodyPart): List[FlowWithSource[_, ChunkStreamPart]] = {
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

    def bodyPartChunks(data: FlowWithSource[_, ByteString]): List[FlowWithSource[_, ChunkStreamPart]] = {
      import FlowGraphImplicits._
      val pub = PublisherSink[ChunkStreamPart]
      val g = FlowGraph { implicit b ⇒
        val concat = Concat[Chunk]
        IterableSource(Chunk(r.get) :: Nil) ~> concat.first
        data.map(Chunk(_)) ~> concat.second
        concat.out ~> pub
      }.run()
      FlowFrom(pub.publisher(g)) :: Nil
    }

    def completePartRendering(): List[FlowWithSource[_, ChunkStreamPart]] =
      bodyPart.entity match {
        case x if x.isKnownEmpty       ⇒ chunkStream(r.get)
        case Strict(_, data)           ⇒ chunkStream((r ~~ data).get)
        case Default(_, _, data)       ⇒ bodyPartChunks(data)
        case IndefiniteLength(_, data) ⇒ bodyPartChunks(data)
      }

    renderBoundary()
    firstBoundaryRendered = true
    renderEntityContentType(r, bodyPart.entity)
    renderHeaders(bodyPart.headers.toList)
    completePartRendering()
  }

  override def onTermination(e: Option[Throwable]): List[FlowWithSource[_, ChunkStreamPart]] =
    if (e.isEmpty && firstBoundaryRendered) {
      val r = new ByteStringRendering(boundary.length + 4)
      r ~~ CrLf ~~ '-' ~~ '-' ~~ boundary ~~ '-' ~~ '-'
      chunkStream(r.get)
    } else Nil

  private def chunkStream(byteString: ByteString) =
    FlowFrom[ChunkStreamPart](Chunk(byteString) :: Nil) :: Nil
}

