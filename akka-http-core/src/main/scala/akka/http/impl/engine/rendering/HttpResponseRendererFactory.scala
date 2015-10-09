/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.rendering

import akka.http.impl.engine.ws.{ FrameEvent, UpgradeToWebsocketResponseHeader }
import akka.http.scaladsl.model.ws.Message

import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import RenderSupport._
import HttpProtocols._
import headers._

/**
 * INTERNAL API
 */
private[http] class HttpResponseRendererFactory(serverHeader: Option[headers.Server],
                                                responseHeaderSizeHint: Int,
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
    val now = currentTimeMillis()
    if (now / 1000 > cachedSeconds) {
      cachedSeconds = now / 1000
      val r = new ByteArrayRendering(48)
      DateTime(now).renderRfc1123DateTimeString(r ~~ headers.Date) ~~ CrLf
      cachedBytes = r.get
      cachedDateHeader = cachedSeconds -> cachedBytes
    }
    cachedBytes
  }

  // split out so we can stabilize by overriding in tests
  protected def currentTimeMillis(): Long = System.currentTimeMillis()

  def newRenderer: HttpResponseRenderer = new HttpResponseRenderer

  final class HttpResponseRenderer extends PushStage[ResponseRenderingContext, Source[ResponseRenderingOutput, Any]] {

    private[this] var closeMode: CloseMode = DontClose // signals what to do after the current response
    private[this] def close: Boolean = closeMode != DontClose
    private[this] def closeIf(cond: Boolean): Unit =
      if (cond) closeMode = CloseConnection

    // need this for testing
    private[http] def isComplete = close

    override def onPush(ctx: ResponseRenderingContext, opCtx: Context[Source[ResponseRenderingOutput, Any]]): SyncDirective = {
      val r = new ByteStringRendering(responseHeaderSizeHint)

      import ctx.response._
      val noEntity = entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD

      def renderStatusLine(): Unit =
        protocol match {
          case `HTTP/1.1` ⇒ if (status eq StatusCodes.OK) r ~~ DefaultStatusLineBytes else r ~~ StatusLineStartBytes ~~ status ~~ CrLf
          case `HTTP/1.0` ⇒ r ~~ protocol ~~ ' ' ~~ status ~~ CrLf
        }

      def render(h: HttpHeader) = r ~~ h ~~ CrLf

      def mustRenderTransferEncodingChunkedHeader =
        entity.isChunked && (!entity.isKnownEmpty || ctx.requestMethod == HttpMethods.HEAD) && (ctx.requestProtocol == `HTTP/1.1`)

      @tailrec def renderHeaders(remaining: List[HttpHeader], alwaysClose: Boolean = false,
                                 connHeader: Connection = null, serverSeen: Boolean = false,
                                 transferEncodingSeen: Boolean = false, dateSeen: Boolean = false): Unit =
        remaining match {
          case head :: tail ⇒ head match {
            case x: `Content-Length` ⇒
              suppressionWarning(log, x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")
              renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)

            case x: `Content-Type` ⇒
              suppressionWarning(log, x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType` instead.")
              renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)

            case x: Date ⇒
              render(x)
              renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen = true)

            case x: `Transfer-Encoding` ⇒
              x.withChunkedPeeled match {
                case None ⇒
                  suppressionWarning(log, head)
                  renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)
                case Some(te) ⇒
                  // if the user applied some custom transfer-encoding we need to keep the header
                  render(if (mustRenderTransferEncodingChunkedHeader) te.withChunked else te)
                  renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen = true, dateSeen)
              }

            case x: Connection ⇒
              val connectionHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)
              renderHeaders(tail, alwaysClose, connectionHeader, serverSeen, transferEncodingSeen, dateSeen)

            case x: Server ⇒
              render(x)
              renderHeaders(tail, alwaysClose, connHeader, serverSeen = true, transferEncodingSeen, dateSeen)

            case x: CustomHeader ⇒
              if (!x.suppressRendering) render(x)
              renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)

            case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
              (x is "date") || (x is "server") || (x is "connection") ⇒
              suppressionWarning(log, x, "illegal RawHeader")
              renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)

            case x ⇒
              render(x)
              renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)
          }

          case Nil ⇒
            if (!serverSeen) renderDefaultServerHeader(r)
            if (!dateSeen) r ~~ dateHeader

            // Do we close the connection after this response?
            closeIf {
              // if we are prohibited to keep-alive by the spec
              alwaysClose ||
                // if the client wants to close and we don't override
                (ctx.closeRequested && ((connHeader eq null) || !connHeader.hasKeepAlive)) ||
                // if the application wants to close explicitly
                (protocol match {
                  case `HTTP/1.1` ⇒ (connHeader ne null) && connHeader.hasClose
                  case `HTTP/1.0` ⇒ if (connHeader eq null) ctx.requestProtocol == `HTTP/1.1` else !connHeader.hasKeepAlive
                })
            }

            // Do we render an explicit Connection header?
            val renderConnectionHeader =
              protocol == `HTTP/1.0` && !close || protocol == `HTTP/1.1` && close || // if we don't follow the default behavior
                close != ctx.closeRequested || // if we override the client's closing request
                protocol != ctx.requestProtocol // if we reply with a mismatching protocol (let's be very explicit in this case)

            if (renderConnectionHeader)
              r ~~ Connection ~~ (if (close) CloseBytes else KeepAliveBytes) ~~ CrLf
            else if (connHeader != null && connHeader.hasUpgrade) {
              r ~~ connHeader ~~ CrLf
              headers
                .collectFirst { case u: UpgradeToWebsocketResponseHeader ⇒ u }
                .foreach { header ⇒ closeMode = SwitchToWebsocket(header.handler) }
            }
            if (mustRenderTransferEncodingChunkedHeader && !transferEncodingSeen)
              r ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
        }

      def renderContentLengthHeader(contentLength: Long) =
        if (status.allowsEntity) r ~~ `Content-Length` ~~ contentLength ~~ CrLf else r

      def byteStrings(entityBytes: ⇒ Source[ByteString, Any]): Source[ResponseRenderingOutput, Any] =
        renderByteStrings(r, entityBytes, skipEntity = noEntity).map(ResponseRenderingOutput.HttpData(_))

      def completeResponseRendering(entity: ResponseEntity): Source[ResponseRenderingOutput, Any] =
        entity match {
          case HttpEntity.Strict(_, data) ⇒
            renderHeaders(headers.toList)
            renderEntityContentType(r, entity)
            renderContentLengthHeader(data.length) ~~ CrLf

            if (!noEntity) r ~~ data

            Source.single {
              closeMode match {
                case SwitchToWebsocket(handler) ⇒ ResponseRenderingOutput.SwitchToWebsocket(r.get, handler)
                case _                          ⇒ ResponseRenderingOutput.HttpData(r.get)
              }
            }

          case HttpEntity.Default(_, contentLength, data) ⇒
            renderHeaders(headers.toList)
            renderEntityContentType(r, entity)
            renderContentLengthHeader(contentLength) ~~ CrLf
            byteStrings(data.via(CheckContentLengthTransformer.flow(contentLength)))

          case HttpEntity.CloseDelimited(_, data) ⇒
            renderHeaders(headers.toList, alwaysClose = ctx.requestMethod != HttpMethods.HEAD)
            renderEntityContentType(r, entity) ~~ CrLf
            byteStrings(data)

          case HttpEntity.Chunked(contentType, chunks) ⇒
            if (ctx.requestProtocol == `HTTP/1.0`)
              completeResponseRendering(HttpEntity.CloseDelimited(contentType, chunks.map(_.data)))
            else {
              renderHeaders(headers.toList)
              renderEntityContentType(r, entity) ~~ CrLf
              byteStrings(chunks.via(ChunkTransformer.flow))
            }
        }

      renderStatusLine()
      val result = completeResponseRendering(entity)
      if (close)
        opCtx.pushAndFinish(result)
      else
        opCtx.push(result)
    }
  }

  sealed trait CloseMode
  case object DontClose extends CloseMode
  case object CloseConnection extends CloseMode
  case class SwitchToWebsocket(handler: Either[Flow[FrameEvent, FrameEvent, Any], Flow[Message, Message, Any]]) extends CloseMode
}

/**
 * INTERNAL API
 */
private[http] final case class ResponseRenderingContext(
  response: HttpResponse,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeRequested: Boolean = false)

/** INTERNAL API */
private[http] sealed trait ResponseRenderingOutput
/** INTERNAL API */
private[http] object ResponseRenderingOutput {
  private[http] case class HttpData(bytes: ByteString) extends ResponseRenderingOutput
  private[http] case class SwitchToWebsocket(httpResponseBytes: ByteString, handler: Either[Flow[FrameEvent, FrameEvent, Any], Flow[Message, Message, Any]]) extends ResponseRenderingOutput
}
