/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import java.net.InetSocketAddress
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
import headers._

/**
 * INTERNAL API
 */
private[http] class HttpRequestRendererFactory(userAgentHeader: Option[headers.`User-Agent`],
                                               requestHeaderSizeHint: Int,
                                               materializer: FlowMaterializer,
                                               log: LoggingAdapter) {

  def newRenderer: HttpRequestRenderer = new HttpRequestRenderer

  final class HttpRequestRenderer extends Transformer[RequestRenderingContext, Producer[ByteString]] {

    def onNext(ctx: RequestRenderingContext): immutable.Seq[Producer[ByteString]] = {
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
                                 userAgentSeen: Boolean = false): Unit =
        remaining match {
          case head :: tail ⇒ head match {
            case x: `Content-Length` ⇒
              suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")
              renderHeaders(tail, hostHeaderSeen, userAgentSeen)

            case x: `Content-Type` ⇒
              suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpRequest.entity.contentType` instead.")
              renderHeaders(tail, hostHeaderSeen, userAgentSeen)

            case `Transfer-Encoding`(_) ⇒
              suppressionWarning(log, head)
              renderHeaders(tail, hostHeaderSeen, userAgentSeen)

            case x: `Host` ⇒
              render(x)
              renderHeaders(tail, hostHeaderSeen = true, userAgentSeen)

            case x: `User-Agent` ⇒
              render(x)
              renderHeaders(tail, hostHeaderSeen, userAgentSeen = true)

            case x: `Raw-Request-URI` ⇒ // we never render this header
              renderHeaders(tail, hostHeaderSeen, userAgentSeen)

            case x: RawHeader if x.lowercaseName == "content-type" ||
              x.lowercaseName == "content-length" ||
              x.lowercaseName == "transfer-encoding" ||
              x.lowercaseName == "host" ||
              x.lowercaseName == "user-agent" ⇒
              suppressionWarning(log, x, "illegal RawHeader")
              renderHeaders(tail, hostHeaderSeen, userAgentSeen)

            case x ⇒
              render(x)
              renderHeaders(tail, hostHeaderSeen, userAgentSeen)
          }

          case Nil ⇒
            if (!hostHeaderSeen) r ~~ Host(ctx.serverAddress) ~~ CrLf
            if (!userAgentSeen && userAgentHeader.isDefined) r ~~ userAgentHeader.get ~~ CrLf
        }

      def renderContentLength(contentLength: Long): Unit = {
        if (method.isEntityAccepted) r ~~ `Content-Length` ~~ contentLength ~~ CrLf
        r ~~ CrLf
      }

      def completeRequestRendering(): immutable.Seq[Producer[ByteString]] =
        entity match {
          case HttpEntity.Strict(contentType, data) ⇒
            renderContentLength(data.length)
            SynchronousProducerFromIterable(r.get :: data :: Nil) :: Nil

          case HttpEntity.Default(contentType, contentLength, data) ⇒
            renderContentLength(contentLength)
            renderByteStrings(r,
              Flow(data).transform(new CheckContentLengthTransformer(contentLength)).toProducer(materializer),
              materializer)

          case HttpEntity.Chunked(contentType, chunks) ⇒
            r ~~ `Transfer-Encoding` ~~ Chunked ~~ CrLf ~~ CrLf
            renderByteStrings(r, Flow(chunks).transform(new ChunkTransformer).toProducer(materializer), materializer)
        }

      renderRequestLine()
      renderHeaders(headers.toList)
      renderEntityContentType(r, entity)
      if (entity.isKnownEmpty) {
        renderContentLength(0)
        SynchronousProducerFromIterable(r.get :: Nil) :: Nil
      } else completeRequestRendering()
    }
  }
}

/**
 * INTERNAL API
 */
private[http] final case class RequestRenderingContext(request: HttpRequest, serverAddress: InetSocketAddress)