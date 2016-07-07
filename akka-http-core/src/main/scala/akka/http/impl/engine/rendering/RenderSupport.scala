/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.parboiled2.CharUtils
import akka.stream.{ Attributes, SourceShape }
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart

import akka.stream.stage.{ Context, GraphStage, SyncDirective, TerminationDirective }
import akka.stream._
import akka.stream.scaladsl.{ Sink, Source, Flow, Keep }
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

  def CancelSecond[T, Mat](first: Source[T, Mat], second: Source[T, Any]): Source[T, Mat] = {
    Source.fromGraph(GraphDSL.create(first) { implicit b ⇒ frst ⇒
      import GraphDSL.Implicits._
      second ~> Sink.cancelled
      SourceShape(frst.out)
    })
  }

  def renderEntityContentType(r: Rendering, entity: HttpEntity) =
    if (entity.contentType != ContentTypes.NoContentType) r ~~ headers.`Content-Type` ~~ entity.contentType ~~ CrLf
    else r

  def renderByteStrings(r: ByteStringRendering, entityBytes: ⇒ Source[ByteString, Any],
                        skipEntity: Boolean = false): Source[ByteString, Any] = {
    val messageStart = Source.single(r.get)
    val messageBytes =
      if (!skipEntity) (messageStart ++ entityBytes).mapMaterializedValue(_ ⇒ ())
      else CancelSecond(messageStart, entityBytes)
    messageBytes
  }

  object ChunkTransformer {
    val flow = Flow.fromGraph(new ChunkTransformer).named("renderChunks")
  }

  class ChunkTransformer extends GraphStage[FlowShape[HttpEntity.ChunkStreamPart, ByteString]] {
    val out: Outlet[ByteString] = Outlet("ChunkTransformer.out")
    val in: Inlet[HttpEntity.ChunkStreamPart] = Inlet("ChunkTransformer.in")
    val shape: FlowShape[HttpEntity.ChunkStreamPart, ByteString] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          val bytes = renderChunk(chunk)
          push(out, bytes)
          if (chunk.isLastChunk) completeStage()
        }

        override def onPull(): Unit = pull(in)

        override def onUpstreamFinish(): Unit = {
          emit(out, defaultLastChunkBytes)
          completeStage()
        }
        setHandlers(in, out, this)
      }
  }

  object CheckContentLengthTransformer {
    def flow(contentLength: Long) = Flow[ByteString].via(new CheckContentLengthTransformer(contentLength))
  }

  final class CheckContentLengthTransformer(length: Long) extends SimpleLinearGraphStage[ByteString] {
    override def initialAttributes: Attributes = Attributes.name("CheckContentLength")

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        override def toString = s"CheckContentLength(sent=$sent)"

        private var sent = 0L

        override def onPush(): Unit = {
          val elem = grab(in)
          sent += elem.length
          if (sent <= length) {
            push(out, elem)
          } else {
            failStage(InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity data stream amounts to more bytes"))
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (sent < length) {
            failStage(InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity data stream amounts to ${length - sent} bytes less"))
          } else {
            completeStage()
          }
        }

        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }

    override def toString = "CheckContentLength"
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
