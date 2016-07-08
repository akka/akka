/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import java.nio.charset.Charset
import akka.parboiled2.util.Base64

import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.engine.rendering.RenderSupport._
import akka.http.impl.util._
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString
import HttpEntity._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import java.util.concurrent.ThreadLocalRandom

/**
 * INTERNAL API
 */
private[http] object BodyPartRenderer {

  def streamed(
    boundary:            String,
    nioCharset:          Charset,
    partHeadersSizeHint: Int,
    log:                 LoggingAdapter): GraphStage[FlowShape[Multipart.BodyPart, Source[ChunkStreamPart, Any]]] =
    new GraphStage[FlowShape[Multipart.BodyPart, Source[ChunkStreamPart, Any]]] {
      var firstBoundaryRendered = false

      val in: Inlet[Multipart.BodyPart] = Inlet("BodyPartRenderer.in")
      val out: Outlet[Source[ChunkStreamPart, Any]] = Outlet("BodyPartRenderer.out")
      override val shape: FlowShape[Multipart.BodyPart, Source[ChunkStreamPart, Any]] = FlowShape(in, out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) with InHandler with OutHandler {
          override def onPush(): Unit = {
            val r = new CustomCharsetByteStringRendering(nioCharset, partHeadersSizeHint)

            def bodyPartChunks(data: Source[ByteString, Any]): Source[ChunkStreamPart, Any] = {
              val entityChunks = data.map[ChunkStreamPart](Chunk(_))
              (chunkStream(r.get) ++ entityChunks).mapMaterializedValue((_) ⇒ ())
            }

            def completePartRendering(entity: HttpEntity): Source[ChunkStreamPart, Any] =
              entity match {
                case x if x.isKnownEmpty       ⇒ chunkStream(r.get)
                case Strict(_, data)           ⇒ chunkStream((r ~~ data).get)
                case Default(_, _, data)       ⇒ bodyPartChunks(data)
                case IndefiniteLength(_, data) ⇒ bodyPartChunks(data)
              }

            renderBoundary(r, boundary, suppressInitialCrLf = !firstBoundaryRendered)
            firstBoundaryRendered = true

            val bodyPart = grab(in)
            renderEntityContentType(r, bodyPart.entity)
            renderHeaders(r, bodyPart.headers, log)

            push(out, completePartRendering(bodyPart.entity))
          }

          override def onPull(): Unit =
            if (isClosed(in) && firstBoundaryRendered)
              completeRendering()
            else if (isClosed(in)) completeStage()
            else pull(in)

          override def onUpstreamFinish(): Unit =
            if (isAvailable(out) && firstBoundaryRendered) completeRendering()

          private def completeRendering(): Unit = {
            val r = new ByteStringRendering(boundary.length + 4)
            renderFinalBoundary(r, boundary)
            push(out, chunkStream(r.get))
            completeStage()
          }

          setHandlers(in, out, this)
        }

      private def chunkStream(byteString: ByteString): Source[ChunkStreamPart, Any] =
        Source.single(Chunk(byteString))

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

  private def renderBoundary(r: Rendering, boundary: String, suppressInitialCrLf: Boolean): Unit = {
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

  /**
   * Creates a new random number of the given length and base64 encodes it (using a custom "safe" alphabet).
   */
  def randomBoundary(length: Int = 18, random: java.util.Random = ThreadLocalRandom.current()): String = {
    val array = new Array[Byte](length)
    random.nextBytes(array)
    Base64.custom.encodeToString(array, false)
  }

  /**
   * Creates a new random number of default length and base64 encodes it (using a custom "safe" alphabet).
   */
  def randomBoundaryWithDefaults(): String = randomBoundary()

  /**
   * Creates a new random number of the given length and base64 encodes it (using a custom "safe" alphabet).
   */
  def randomBoundaryWithDefaultRandom(length: Int): String = randomBoundary(length)
}
