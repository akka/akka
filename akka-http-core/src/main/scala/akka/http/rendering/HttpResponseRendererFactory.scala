/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import org.reactivestreams.Publisher
import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.scaladsl.Flow
import akka.stream.impl.SynchronousPublisherFromIterable
import akka.stream.{ FlowMaterializer, Transformer }
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

  private val renderDefaultServerHeader: Rendering ⇒ Unit =
    serverHeader match {
      case Some(h) ⇒
        val bytes = (new ByteArrayRendering(32) ~~ h ~~ CrLf).get
        _ ~~ bytes
      case None ⇒ _ ⇒ ()
    }

  // as an optimization we cache the Date header of the last second here
  @volatile private[this] var cachedDateHeader: (Long, Array[Byte]) = (0L, null)

  private def dateHeader: Array[Byte] = {
    var (cachedSeconds, cachedBytes) = cachedDateHeader
    val now = System.currentTimeMillis
    if (now - 1000 > cachedSeconds) {
      cachedSeconds = now / 1000
      val r = new ByteArrayRendering(48)
      dateTime(now).renderRfc1123DateTimeString(r ~~ headers.Date) ~~ CrLf
      cachedBytes = r.get
      cachedDateHeader = cachedSeconds -> cachedBytes
    }
    cachedBytes
  }

  protected def dateTime(now: Long) = DateTime(now) // split out so we can stabilize by overriding in tests

  def newRenderer: HttpResponseRenderer = new HttpResponseRenderer

  final class HttpResponseRenderer extends Transformer[ResponseRenderingContext, Publisher[ByteString]] {
    private[this] var close = false // signals whether the connection is to be closed after the current response

    override def isComplete = close

    def onNext(ctx: ResponseRenderingContext): List[Publisher[ByteString]] = {
      val r = new ByteStringRendering(responseHeaderSizeHint)

      import ctx.response._
      val noEntity = entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD

      def renderStatusLine(): Unit =
        if (status eq StatusCodes.OK) r ~~ DefaultStatusLineBytes else r ~~ StatusLineStartBytes ~~ status ~~ CrLf

      def render(h: HttpHeader) = r ~~ h ~~ CrLf

      @tailrec def renderHeaders(remaining: List[HttpHeader], alwaysClose: Boolean = false,
                                 connHeader: Connection = null, serverHeaderSeen: Boolean = false): Unit =
        remaining match {
          case head :: tail ⇒ head match {
            case x: `Content-Length` ⇒
              suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")
              renderHeaders(tail, alwaysClose, connHeader, serverHeaderSeen)

            case x: `Content-Type` ⇒
              suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType` instead.")
              renderHeaders(tail, alwaysClose, connHeader, serverHeaderSeen)

            case `Transfer-Encoding`(_) | Date(_) ⇒
              suppressionWarning(log, head)
              renderHeaders(tail, alwaysClose, connHeader, serverHeaderSeen)

            case x: `Connection` ⇒
              val connectionHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)
              renderHeaders(tail, alwaysClose, connectionHeader, serverHeaderSeen)

            case x: `Server` ⇒
              render(x)
              renderHeaders(tail, alwaysClose, connHeader, serverHeaderSeen = true)

            case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
              (x is "date") || (x is "server") || (x is "connection") ⇒
              suppressionWarning(log, x, "illegal RawHeader")
              renderHeaders(tail, alwaysClose, connHeader, serverHeaderSeen)

            case x ⇒
              render(x)
              renderHeaders(tail, alwaysClose, connHeader, serverHeaderSeen)
          }

          case Nil ⇒
            if (!serverHeaderSeen) renderDefaultServerHeader(r)
            r ~~ dateHeader
            close = alwaysClose ||
              ctx.closeAfterResponseCompletion || // request wants to close
              (connHeader != null && connHeader.hasClose) // application wants to close
            ctx.requestProtocol match {
              case `HTTP/1.0` if !close ⇒ r ~~ Connection ~~ KeepAliveBytes ~~ CrLf
              case `HTTP/1.1` if close  ⇒ r ~~ Connection ~~ CloseBytes ~~ CrLf
              case _                    ⇒ // no need for rendering
            }
        }

      def byteStrings(entityBytes: ⇒ Publisher[ByteString]): List[Publisher[ByteString]] =
        renderByteStrings(r, entityBytes, materializer, skipEntity = noEntity)

      def completeResponseRendering(entity: HttpEntity): List[Publisher[ByteString]] =
        entity match {
          case HttpEntity.Strict(_, data) ⇒
            renderHeaders(headers.toList)
            renderEntityContentType(r, entity)
            r ~~ `Content-Length` ~~ data.length ~~ CrLf ~~ CrLf
            val entityBytes = if (noEntity) Nil else data :: Nil
            SynchronousPublisherFromIterable(r.get :: entityBytes) :: Nil

          case HttpEntity.Default(_, contentLength, data) ⇒
            renderHeaders(headers.toList)
            renderEntityContentType(r, entity)
            r ~~ `Content-Length` ~~ contentLength ~~ CrLf ~~ CrLf
            byteStrings(Flow(data).transform(new CheckContentLengthTransformer(contentLength)).toPublisher(materializer))

          case HttpEntity.CloseDelimited(_, data) ⇒
            renderHeaders(headers.toList, alwaysClose = true)
            renderEntityContentType(r, entity)
            r ~~ CrLf
            byteStrings(data)

          case HttpEntity.Chunked(contentType, chunks) ⇒
            if (ctx.requestProtocol == `HTTP/1.0`)
              completeResponseRendering(HttpEntity.CloseDelimited(contentType, Flow(chunks).map(_.data).toPublisher(materializer)))
            else {
              renderHeaders(headers.toList)
              renderEntityContentType(r, entity)
              if (!entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD)
                r ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
              r ~~ CrLf
              byteStrings(Flow(chunks).transform(new ChunkTransformer).toPublisher(materializer))
            }
        }

      renderStatusLine()
      completeResponseRendering(entity)
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
