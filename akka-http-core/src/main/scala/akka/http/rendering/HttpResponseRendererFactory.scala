/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import org.reactivestreams.api.Producer
import scala.annotation.tailrec
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, Transformer }
import akka.stream.impl.SynchronousProducerFromIterable
import akka.http.model._
import akka.http.util._
import RenderSupport._
import HttpProtocols._
import headers._

/**
 * INTERNAL API
 */
private[http] class HttpResponseRendererFactory(serverHeader: Option[headers.Server],
                                                responseHeaderSizeHint: Int,
                                                materializer: FlowMaterializer,
                                                log: LoggingAdapter) {

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

      @tailrec def renderHeaders(remaining: List[HttpHeader], alwaysClose: Boolean = false,
                                 connHeader: Connection = null): Unit = {
        def render(h: HttpHeader) = r ~~ h ~~ CrLf
        def suppressionWarning(h: HttpHeader, msg: String = "the akka-http-core layer sets this header automatically!"): Unit =
          log.warning("Explicitly set response header '{}' is ignored, {}", h, msg)

        remaining match {
          case head :: tail ⇒ head match {
            case x: `Content-Length` ⇒
              suppressionWarning(x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")
              renderHeaders(tail, alwaysClose, connHeader)

            case x: `Content-Type` ⇒
              suppressionWarning(x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType`, instead.")
              renderHeaders(tail, alwaysClose, connHeader)

            case `Transfer-Encoding`(_) | Date(_) | Server(_) ⇒
              suppressionWarning(head)
              renderHeaders(tail, alwaysClose, connHeader)

            case x: `Connection` ⇒
              val connectionHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)
              renderHeaders(tail, alwaysClose, connectionHeader)

            case x: RawHeader if x.lowercaseName == "content-type" ||
              x.lowercaseName == "content-length" ||
              x.lowercaseName == "transfer-encoding" ||
              x.lowercaseName == "date" ||
              x.lowercaseName == "server" ||
              x.lowercaseName == "connection" ⇒
              suppressionWarning(x, "illegal RawHeader")
              renderHeaders(tail, alwaysClose, connHeader)

            case x ⇒
              render(x)
              renderHeaders(tail, alwaysClose, connHeader)
          }

          case Nil ⇒
            close = alwaysClose ||
              ctx.closeAfterResponseCompletion || // request wants to close
              (connHeader != null && connHeader.hasClose) // application wants to close
            ctx.requestProtocol match {
              case `HTTP/1.0` if !close ⇒ r ~~ Connection ~~ KeepAlive ~~ CrLf
              case `HTTP/1.1` if close  ⇒ r ~~ Connection ~~ Close ~~ CrLf
              case _                    ⇒ // no need for rendering
            }
        }
      }

      def renderByteStrings(entityBytes: ⇒ Producer[ByteString]): immutable.Seq[Producer[ByteString]] = {
        val messageStart = SynchronousProducerFromIterable(r.get :: Nil)
        val messageBytes =
          if (!entity.isKnownEmpty && ctx.requestMethod != HttpMethods.HEAD)
            Flow(messageStart).concat(entityBytes).toProducer(materializer)
          else messageStart
        messageBytes :: Nil
      }

      def renderContentType(entity: HttpEntity): Unit =
        if (!entity.isKnownEmpty && entity.contentType != ContentTypes.NoContentType)
          r ~~ `Content-Type` ~~ entity.contentType ~~ CrLf

      def renderEntity(entity: HttpEntity): immutable.Seq[Producer[ByteString]] =
        entity match {
          case HttpEntity.Strict(contentType, data) ⇒
            renderHeaders(headers.toList)
            renderContentType(entity)
            r ~~ `Content-Length` ~~ data.length ~~ CrLf ~~ CrLf
            if (!entity.isKnownEmpty && ctx.requestMethod != HttpMethods.HEAD) r ~~ data
            SynchronousProducerFromIterable(r.get :: Nil) :: Nil

          case HttpEntity.Default(contentType, contentLength, data) ⇒
            renderHeaders(headers.toList)
            renderContentType(entity)
            r ~~ `Content-Length` ~~ contentLength ~~ CrLf ~~ CrLf
            renderByteStrings(Flow(data).transform(new CheckContentLengthTransformer(contentLength)).toProducer(materializer))

          case HttpEntity.CloseDelimited(contentType, data) ⇒
            renderHeaders(headers.toList, alwaysClose = true)
            renderContentType(entity)
            r ~~ CrLf
            renderByteStrings(data)

          case HttpEntity.Chunked(contentType, chunks) ⇒
            if (ctx.requestProtocol == `HTTP/1.0`)
              renderEntity(HttpEntity.CloseDelimited(contentType, Flow(chunks).map(_.data).toProducer(materializer)))
            else {
              renderHeaders(headers.toList)
              renderContentType(entity)
              if (!entity.isKnownEmpty) r ~~ `Transfer-Encoding` ~~ Chunked ~~ CrLf
              r ~~ CrLf
              renderByteStrings(Flow(chunks).transform(new ChunkTransformer).toProducer(materializer))
            }
        }

      renderEntity(entity)
    }
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
        throw new InvalidContentLengthException(s"Response had declared Content-Length $length but entity chunk stream amounts to more bytes")
      elem :: Nil
    }

    override def onTermination(e: Option[Throwable]): immutable.Seq[ByteString] = {
      if (sent < length)
        throw new InvalidContentLengthException(s"Response had declared Content-Length $length but entity chunk stream amounts to ${length - sent} bytes less")
      Nil
    }
  }
}

/**
 * INTERNAL API
 */
private[http] final case class ResponseRenderingContext(
  response: HttpResponse,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeAfterResponseCompletion: Boolean = false)
