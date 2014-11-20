/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import akka.parboiled2.CharUtils
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.http.model._
import akka.http.util._
import org.reactivestreams.Subscriber

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

  // This hooks into the materialization to cancel the not needed second source. This helper class
  // allows us to not take a FlowMaterializer but delegate the cancellation to the point when the whole stream
  // materializes
  private case class CancelSecond[T](first: Source[T], second: Source[T]) extends SimpleActorFlowSource[T] {
    override def attach(flowSubscriber: Subscriber[T], materializer: ActorBasedFlowMaterializer, flowName: String): Unit = {
      first.to(Sink(flowSubscriber)).run()(materializer)
      second.to(Sink.cancelled).run()(materializer)
    }
  }

  def renderEntityContentType(r: Rendering, entity: HttpEntity) =
    if (entity.contentType != ContentTypes.NoContentType) r ~~ headers.`Content-Type` ~~ entity.contentType ~~ CrLf
    else r

  def renderByteStrings(r: ByteStringRendering, entityBytes: ⇒ Source[ByteString],
                        skipEntity: Boolean = false): Source[ByteString] = {
    val messageStart = Source.singleton(r.get)
    val messageBytes =
      if (!skipEntity) messageStart ++ entityBytes
      else CancelSecond(messageStart, entityBytes)
    messageBytes
  }

  class ChunkTransformer extends StatefulStage[HttpEntity.ChunkStreamPart, ByteString] {
    var lastChunkSeen = false

    override def initial = new State {
      override def onPush(chunk: HttpEntity.ChunkStreamPart, ctx: Context[ByteString]): Directive = {
        if (chunk.isLastChunk)
          lastChunkSeen = true
        ctx.push(renderChunk(chunk))
      }
    }

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective =
      if (lastChunkSeen) super.onUpstreamFinish(ctx)
      else terminationEmit(Iterator.single(defaultLastChunkBytes), ctx)
  }

  class CheckContentLengthTransformer(length: Long) extends PushStage[ByteString, ByteString] {
    var sent = 0L

    override def onPush(elem: ByteString, ctx: Context[ByteString]): Directive = {
      sent += elem.length
      if (sent > length)
        throw new InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity data stream amounts to more bytes")
      ctx.push(elem)
    }

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
      if (sent < length)
        throw new InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity data stream amounts to ${length - sent} bytes less")
      ctx.finish()
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
