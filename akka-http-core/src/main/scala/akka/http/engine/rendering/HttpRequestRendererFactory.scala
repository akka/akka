/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import java.net.InetSocketAddress
import akka.stream.Transformer
import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.scaladsl2.{ FlowWithSource, FlowFrom, FlowMaterializer }
import akka.http.model._
import akka.http.util._
import RenderSupport._
import headers._

/**
 * INTERNAL API
 */
private[http] class HttpRequestRendererFactory(userAgentHeader: Option[headers.`User-Agent`],
                                               requestHeaderSizeHint: Int,
                                               log: LoggingAdapter)(implicit fm: FlowMaterializer) {

  def newRenderer: HttpRequestRenderer = new HttpRequestRenderer

  final class HttpRequestRenderer extends Transformer[RequestRenderingContext, FlowWithSource[_, ByteString]] {

    def onNext(ctx: RequestRenderingContext): List[FlowWithSource[_, ByteString]] = {
      val r = new ByteStringRendering(requestHeaderSizeHint)
      import ctx.request._

      def renderRequestLine(): Unit = {
        r ~~ method ~~ ' '
        val rawRequestUriRendered = headers.exists {
          case `Raw-Request-URI`(rawUri) ⇒
            r ~~ rawUri; true
          case _ ⇒ false
        }
        if (!rawRequestUriRendered) UriRendering.renderUriWithoutFragment(r, uri, UTF8)
        r ~~ ' ' ~~ protocol ~~ CrLf
      }

      def render(h: HttpHeader) = r ~~ h ~~ CrLf

      @tailrec def renderHeaders(remaining: List[HttpHeader], hostHeaderSeen: Boolean = false,
                                 userAgentSeen: Boolean = false, transferEncodingSeen: Boolean = false): Unit =
        remaining match {
          case head :: tail ⇒ head match {
            case x: `Content-Length` ⇒
              suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")
              renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)

            case x: `Content-Type` ⇒
              suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpRequest.entity.contentType` instead.")
              renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)

            case x: `Transfer-Encoding` ⇒
              x.withChunkedPeeled match {
                case None ⇒
                  suppressionWarning(log, head)
                  renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)
                case Some(te) ⇒
                  // if the user applied some custom transfer-encoding we need to keep the header
                  render(if (entity.isChunked && !entity.isKnownEmpty) te.withChunked else te)
                  renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen = true)
              }

            case x: `Host` ⇒
              render(x)
              renderHeaders(tail, hostHeaderSeen = true, userAgentSeen, transferEncodingSeen)

            case x: `User-Agent` ⇒
              render(x)
              renderHeaders(tail, hostHeaderSeen, userAgentSeen = true, transferEncodingSeen)

            case x: `Raw-Request-URI` ⇒ // we never render this header
              renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)

            case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
              (x is "host") || (x is "user-agent") ⇒
              suppressionWarning(log, x, "illegal RawHeader")
              renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)

            case x ⇒
              render(x)
              renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)
          }

          case Nil ⇒
            if (!hostHeaderSeen) r ~~ Host(ctx.serverAddress) ~~ CrLf
            if (!userAgentSeen && userAgentHeader.isDefined) r ~~ userAgentHeader.get ~~ CrLf
            if (entity.isChunked && !entity.isKnownEmpty && !transferEncodingSeen)
              r ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
        }

      def renderContentLength(contentLength: Long): Unit = {
        if (method.isEntityAccepted) r ~~ `Content-Length` ~~ contentLength ~~ CrLf
        r ~~ CrLf
      }

      def completeRequestRendering(): List[FlowWithSource[_, ByteString]] =
        entity match {
          case x if x.isKnownEmpty ⇒
            renderContentLength(0)
            FlowFrom(r.get :: Nil) :: Nil

          case HttpEntity.Strict(_, data) ⇒
            renderContentLength(data.length)
            FlowFrom(r.get :: data :: Nil) :: Nil

          case HttpEntity.Default(_, contentLength, data) ⇒
            renderContentLength(contentLength)
            renderByteStrings(r,
              data.transform("checkContentLenght", () ⇒ new CheckContentLengthTransformer(contentLength)))

          case HttpEntity.Chunked(_, chunks) ⇒
            r ~~ CrLf
            renderByteStrings(r, chunks.transform("chunkTransform", () ⇒ new ChunkTransformer))
        }

      renderRequestLine()
      renderHeaders(headers.toList)
      renderEntityContentType(r, entity)
      completeRequestRendering()
    }
  }
}

/**
 * INTERNAL API
 */
private[http] final case class RequestRenderingContext(request: HttpRequest, serverAddress: InetSocketAddress)