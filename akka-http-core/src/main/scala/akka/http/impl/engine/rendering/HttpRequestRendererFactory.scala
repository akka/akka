/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.NotUsed
import akka.http.impl.engine.parsing.HttpResponseParser
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.model.RequestEntityAcceptance._

import scala.concurrent.Future
import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model._
import akka.http.impl.util._
import RenderSupport._
import headers._

/**
 * INTERNAL API
 */
private[http] class HttpRequestRendererFactory(userAgentHeader: Option[headers.`User-Agent`],
                                               requestHeaderSizeHint: Int,
                                               log: LoggingAdapter) {
  import HttpRequestRendererFactory.RequestRenderingOutput

  def renderToSource(ctx: RequestRenderingContext): Source[ByteString, Any] = render(ctx).byteStream

  def render(ctx: RequestRenderingContext): RequestRenderingOutput = {
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

          case x: CustomHeader ⇒
            if (x.renderInRequests) render(x)
            renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)

          case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
            (x is "host") || (x is "user-agent") ⇒
            suppressionWarning(log, x, "illegal RawHeader")
            renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)

          case x ⇒
            if (x.renderInRequests) render(x)
            else log.warning("HTTP header '{}' is not allowed in requests", x)
            renderHeaders(tail, hostHeaderSeen, userAgentSeen, transferEncodingSeen)
        }

        case Nil ⇒
          if (!hostHeaderSeen) r ~~ ctx.hostHeader ~~ CrLf
          if (!userAgentSeen && userAgentHeader.isDefined) r ~~ userAgentHeader.get ~~ CrLf
          if (entity.isChunked && !entity.isKnownEmpty && !transferEncodingSeen)
            r ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
      }

    def renderContentLength(contentLength: Long) =
      if (method.isEntityAccepted && (contentLength > 0 || method.requestEntityAcceptance == Expected)) r ~~ `Content-Length` ~~ contentLength ~~ CrLf else r

    def renderStreamed(body: Source[ByteString, Any]): RequestRenderingOutput = {
      val headerPart = Source.single(r.get)
      val stream = ctx.sendEntityTrigger match {
        case None ⇒ headerPart ++ body
        case Some(future) ⇒
          val barrier = Source.fromFuture(future).drop(1).asInstanceOf[Source[ByteString, Any]]
          (headerPart ++ barrier ++ body).recoverWith { case HttpResponseParser.OneHundredContinueError ⇒ Source.empty }
      }
      RequestRenderingOutput.Streamed(stream)
    }

    def completeRequestRendering(): RequestRenderingOutput =
      entity match {
        case x if x.isKnownEmpty ⇒
          renderContentLength(0) ~~ CrLf
          RequestRenderingOutput.Strict(r.get)

        case HttpEntity.Strict(_, data) ⇒
          renderContentLength(data.length) ~~ CrLf
          if (ctx.sendEntityTrigger.isDefined) renderStreamed(Source.single(data))
          else RequestRenderingOutput.Strict(r.get ++ data)

        case HttpEntity.Default(_, contentLength, data) ⇒
          renderContentLength(contentLength) ~~ CrLf
          renderStreamed(data.via(CheckContentLengthTransformer.flow(contentLength)))

        case HttpEntity.Chunked(_, chunks) ⇒
          r ~~ CrLf
          renderStreamed(chunks.via(ChunkTransformer.flow))
      }

    renderRequestLine()
    renderHeaders(headers.toList)
    renderEntityContentType(r, entity)
    completeRequestRendering()
  }

  def renderStrict(ctx: RequestRenderingContext): ByteString =
    render(ctx) match {
      case RequestRenderingOutput.Strict(bytes) ⇒ bytes
      case _: RequestRenderingOutput.Streamed ⇒
        throw new IllegalArgumentException(s"Request entity was not Strict but ${ctx.request.entity.getClass.getSimpleName}")
    }
}

private[http] object HttpRequestRendererFactory {
  def renderStrict(ctx: RequestRenderingContext, settings: ClientConnectionSettings, log: LoggingAdapter): ByteString =
    new HttpRequestRendererFactory(settings.userAgentHeader, settings.requestHeaderSizeHint, log).renderStrict(ctx)

  sealed trait RequestRenderingOutput {
    def byteStream: Source[ByteString, Any]
  }
  object RequestRenderingOutput {
    case class Strict(bytes: ByteString) extends RequestRenderingOutput {
      def byteStream: Source[ByteString, Any] = Source.single(bytes)
    }
    case class Streamed(byteStream: Source[ByteString, Any]) extends RequestRenderingOutput
  }
}

/**
 * INTERNAL API
 *
 * @param request the request to be rendered
 * @param hostHeader the host header to render (not necessarily contained in the request.headers)
 * @param sendEntityTrigger defined when the request has a `Expect: 100-continue` header; in this case the future will
 *                          be completed successfully when the request entity is allowed to go out onto the wire;
 *                          if the future is completed with an error the connection is to be closed.
 */
private[http] final case class RequestRenderingContext(
  request: HttpRequest,
  hostHeader: Host,
  sendEntityTrigger: Option[Future[NotUsed]] = None)
