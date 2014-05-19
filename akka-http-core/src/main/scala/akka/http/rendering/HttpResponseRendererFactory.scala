/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import org.reactivestreams.api.Producer
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.scaladsl.{ Flow, StreamProducer }
import akka.stream.{ FlowMaterializer, Transformer }
import akka.http.model._
import akka.http.util._
import RenderSupport._
import HttpProtocols._
import headers._

class HttpResponseRendererFactory(serverHeader: Option[headers.Server],
                                  chunklessStreaming: Boolean,
                                  responseHeaderSizeHint: Int,
                                  materializer: FlowMaterializer,
                                  log: LoggingAdapter)(implicit ec: ExecutionContext) {

  private val serverHeaderPlusDateColonSP: Array[Byte] =
    serverHeader match {
      case None         ⇒ "Date: ".getAsciiBytes
      case Some(header) ⇒ (new ByteArrayRendering(64) ~~ header ~~ Rendering.CrLf ~~ "Date: ").get
    }

  // as an optimization we cache the ServerAndDateHeader of the last second here
  @volatile private[this] var cachedServerAndDateHeader: (Long, Array[Byte]) = (0L, null)

  private def serverAndDateHeader: Array[Byte] = {
    var (cachedSeconds, cachedBytes) = cachedServerAndDateHeader
    val now = System.currentTimeMillis
    if (now / 1000 != cachedSeconds) {
      cachedSeconds = now / 1000
      val r = new ByteArrayRendering(serverHeaderPlusDateColonSP.length + 31)
      dateTime(now).renderRfc1123DateTimeString(r ~~ serverHeaderPlusDateColonSP) ~~ CrLf
      cachedBytes = r.get
      cachedServerAndDateHeader = cachedSeconds -> cachedBytes
    }
    cachedBytes
  }

  protected def dateTime(now: Long) = DateTime(now) // split out so we can stabilize by overriding in tests

  def newRenderer: HttpResponseRenderer = new HttpResponseRenderer

  class HttpResponseRenderer extends Transformer[ResponseRenderingContext, Producer[ByteString]] {
    private[this] var close = false // signals whether the connection is to be closed after the current response

    override def isComplete = close

    def onNext(ctx: ResponseRenderingContext): immutable.Seq[Producer[ByteString]] = {
      val r = new ByteStringRendering(responseHeaderSizeHint)

      import ctx.response._
      if (status eq StatusCodes.OK) r ~~ DefaultStatusLine else r ~~ StatusLineStart ~~ status ~~ CrLf
      r ~~ serverAndDateHeader

      @tailrec def renderHeaders(remaining: List[HttpHeader], contentLengthAllowed: Boolean, alwaysClose: Boolean = false,
                                 userContentType: Boolean = false, connHeader: Connection = null): Unit = {
        def render(h: HttpHeader) = r ~~ h ~~ CrLf
        def suppressionWarning(h: HttpHeader, msg: String = "the akka-http-core layer sets this header automatically!"): Unit =
          log.warning("Explicitly set response header '{}' is ignored, {}", h, msg)

        remaining match {
          case head :: tail ⇒ head match {
            case x: `Content-Length` ⇒
              if (contentLengthAllowed) render(x)
              else suppressionWarning(x, "either another `Content-Length` header was already" +
                "rendered or a `Content-Length` header is disallowed for this type of response")
              renderHeaders(tail, contentLengthAllowed = false, alwaysClose, userContentType, connHeader)

            case `Content-Type`(_) | `Transfer-Encoding`(_) | Date(_) | Server(_) ⇒
              suppressionWarning(head)
              renderHeaders(tail, contentLengthAllowed, alwaysClose, userContentType, connHeader)

            case x: `Connection` ⇒
              val connectionHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)
              renderHeaders(tail, contentLengthAllowed, alwaysClose, userContentType, connectionHeader)

            case x: RawHeader if x.lowercaseName == "content-type" ||
              x.lowercaseName == "content-length" ||
              x.lowercaseName == "transfer-encoding" ||
              x.lowercaseName == "date" ||
              x.lowercaseName == "server" ||
              x.lowercaseName == "connection" ⇒
              suppressionWarning(x, "illegal RawHeader")
              renderHeaders(tail, contentLengthAllowed, alwaysClose, userContentType, connHeader)

            case x ⇒
              render(x)
              renderHeaders(tail, contentLengthAllowed, alwaysClose, userContentType, connHeader)
          }

          case Nil ⇒
            close = alwaysClose ||
              ctx.closeAfterResponseCompletion || // request wants to close
              (connHeader != null && connHeader.hasClose) || // application wants to close
              contentLengthAllowed // chunkless streaming with missing content-length, close needed as data boundary
            ctx.requestProtocol match {
              case `HTTP/1.0` if !close ⇒ r ~~ Connection ~~ KeepAlive ~~ CrLf
              case `HTTP/1.1` if close  ⇒ r ~~ Connection ~~ Close ~~ CrLf
              case _                    ⇒ // no need for rendering
            }
        }
      }

      def renderByteStrings(entityBytes: ⇒ Producer[ByteString]): immutable.Seq[Producer[ByteString]] = {
        val messageStart = StreamProducer(r.get :: Nil)
        val messageBytes =
          if (!entity.isKnownEmpty && ctx.requestMethod != HttpMethods.HEAD)
            Flow(messageStart).concat(entityBytes).toProducer(materializer)
          else messageStart
        messageBytes :: Nil
      }

      def renderContentType(entity: HttpEntity): Unit =
        if (!entity.isKnownEmpty && entity.contentType != ContentTypes.NoContentType)
          r ~~ `Content-Type` ~~ entity.contentType ~~ CrLf

      entity match {
        case HttpEntity.Default(contentType, contentLength, data) ⇒
          renderHeaders(headers.toList, contentLengthAllowed = false)
          renderContentType(entity)
          r ~~ `Content-Length` ~~ contentLength ~~ CrLf ~~ CrLf
          renderByteStrings(data)

        case HttpEntity.CloseDelimited(contentType, data) ⇒
          renderHeaders(headers.toList, contentLengthAllowed = false, alwaysClose = true)
          renderContentType(entity)
          r ~~ CrLf
          renderByteStrings(data)

        case HttpEntity.Chunked(contentType, chunks) ⇒
          val chunkless = chunklessStreaming || ctx.requestProtocol == `HTTP/1.0`
          renderHeaders(headers.toList, contentLengthAllowed = chunkless)
          renderContentType(entity)
          if (!chunkless && !entity.isKnownEmpty) r ~~ `Transfer-Encoding` ~~ Chunked ~~ CrLf
          r ~~ CrLf
          val transformer =
            if (chunkless) new ChunklessChunkTransformer(ctx.response.header[`Content-Length`])
            else new ChunkTransformer
          renderByteStrings(Flow(chunks).transform(transformer).toProducer(materializer))
      }
    }
  }

  class ChunkTransformer extends Transformer[HttpEntity.ChunkStreamPart, ByteString] {
    var lastChunkSeen = false
    def onNext(chunk: HttpEntity.ChunkStreamPart): immutable.Seq[ByteString] = {
      if (chunk.isLastChunk) lastChunkSeen = true
      renderChunk(chunk) :: Nil
    }
    override def isComplete = lastChunkSeen
    override def onComplete = if (lastChunkSeen) Nil else defaultLastChunkBytes :: Nil
  }

  class ChunklessChunkTransformer(clHeader: Option[`Content-Length`]) extends Transformer[HttpEntity.ChunkStreamPart, ByteString] {
    var lastChunkSeen = false
    var remainingBytes = clHeader match {
      case Some(`Content-Length`(x)) ⇒ x
      case None                      ⇒ -1
    }
    def onNext(chunk: HttpEntity.ChunkStreamPart): immutable.Seq[ByteString] =
      if (chunk.isLastChunk) onComplete
      else {
        if (remainingBytes >= 0) {
          remainingBytes -= chunk.data.length
          if (remainingBytes < 0) sys.error(s"Chunkless streaming response has manual header `${clHeader.get}` but " +
            "entity chunk stream amounts to more bytes")
        }
        chunk.data :: Nil
      }
    override def isComplete = lastChunkSeen
    override def onComplete: immutable.Seq[ByteString] =
      if (remainingBytes <= 0) {
        lastChunkSeen = true
        Nil
      } else sys.error(s"Chunkless streaming response has manual header `${clHeader.get}` but " +
        s"entity chunk stream amounts to $remainingBytes bytes less")
  }
}

case class ResponseRenderingContext(
  response: HttpResponse,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeAfterResponseCompletion: Boolean = false)
