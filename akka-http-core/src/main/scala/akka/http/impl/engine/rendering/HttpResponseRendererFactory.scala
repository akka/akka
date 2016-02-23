/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.NotUsed
import akka.http.impl.engine.ws.{ FrameEvent, UpgradeToWebSocketResponseHeader }
import akka.http.scaladsl.model.ws.Message
import akka.stream.{ Outlet, Inlet, Attributes, FlowShape, Graph }

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

  def renderer: Flow[ResponseRenderingContext, ResponseRenderingOutput, NotUsed] = Flow.fromGraph(HttpResponseRenderer)

  object HttpResponseRenderer extends GraphStage[FlowShape[ResponseRenderingContext, ResponseRenderingOutput]] {
    val in = Inlet[ResponseRenderingContext]("in")
    val out = Outlet[ResponseRenderingOutput]("out")
    val shape: FlowShape[ResponseRenderingContext, ResponseRenderingOutput] = FlowShape(in, out)

    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        var closeMode: CloseMode = DontClose // signals what to do after the current response
        def close: Boolean = closeMode != DontClose
        def closeIf(cond: Boolean): Unit = if (cond) closeMode = CloseConnection
        var transferring = false

        setHandler(in, new InHandler {
          override def onPush(): Unit =
            render(grab(in)) match {
              case Strict(outElement) ⇒
                push(out, outElement)
                if (close) completeStage()
              case Streamed(outStream) ⇒ transfer(outStream)
            }

          override def onUpstreamFinish(): Unit =
            if (transferring) closeMode = CloseConnection
            else completeStage()
        })
        val waitForDemandHandler = new OutHandler {
          def onPull(): Unit = pull(in)
        }
        setHandler(out, waitForDemandHandler)
        def transfer(outStream: Source[ResponseRenderingOutput, Any]): Unit = {
          transferring = true
          val sinkIn = new SubSinkInlet[ResponseRenderingOutput]("RenderingSink")
          sinkIn.setHandler(new InHandler {
            override def onPush(): Unit = push(out, sinkIn.grab())
            override def onUpstreamFinish(): Unit =
              if (close) completeStage()
              else {
                transferring = false
                setHandler(out, waitForDemandHandler)
                if (isAvailable(out)) pull(in)
              }
          })
          setHandler(out, new OutHandler {
            override def onPull(): Unit = sinkIn.pull()
            override def onDownstreamFinish(): Unit = {
              completeStage()
              sinkIn.cancel()
            }
          })
          sinkIn.pull()
          outStream.runWith(sinkIn.sink)(interpreter.subFusingMaterializer)
        }

        def render(ctx: ResponseRenderingContext): StrictOrStreamed = {
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
                  if (x.renderInResponses) render(x)
                  renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)

                case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
                  (x is "date") || (x is "server") || (x is "connection") ⇒
                  suppressionWarning(log, x, "illegal RawHeader")
                  renderHeaders(tail, alwaysClose, connHeader, serverSeen, transferEncodingSeen, dateSeen)

                case x ⇒
                  if (x.renderInResponses) render(x)
                  else log.warning("HTTP header '{}' is not allowed in responses", x)
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
                    .collectFirst { case u: UpgradeToWebSocketResponseHeader ⇒ u }
                    .foreach { header ⇒ closeMode = SwitchToWebSocket(header.handler) }
                }
                if (mustRenderTransferEncodingChunkedHeader && !transferEncodingSeen)
                  r ~~ `Transfer-Encoding` ~~ ChunkedBytes ~~ CrLf
            }

          def renderContentLengthHeader(contentLength: Long) =
            if (status.allowsEntity) r ~~ `Content-Length` ~~ contentLength ~~ CrLf else r

          def byteStrings(entityBytes: ⇒ Source[ByteString, Any]): Source[ResponseRenderingOutput, Any] =
            renderByteStrings(r, entityBytes, skipEntity = noEntity).map(ResponseRenderingOutput.HttpData(_))

          def completeResponseRendering(entity: ResponseEntity): StrictOrStreamed =
            entity match {
              case HttpEntity.Strict(_, data) ⇒
                renderHeaders(headers.toList)
                renderEntityContentType(r, entity)
                renderContentLengthHeader(data.length) ~~ CrLf

                if (!noEntity) r ~~ data

                Strict {
                  closeMode match {
                    case SwitchToWebSocket(handler) ⇒ ResponseRenderingOutput.SwitchToWebSocket(r.get, handler)
                    case _                          ⇒ ResponseRenderingOutput.HttpData(r.get)
                  }
                }

              case HttpEntity.Default(_, contentLength, data) ⇒
                renderHeaders(headers.toList)
                renderEntityContentType(r, entity)
                renderContentLengthHeader(contentLength) ~~ CrLf
                Streamed(byteStrings(data.via(CheckContentLengthTransformer.flow(contentLength))))

              case HttpEntity.CloseDelimited(_, data) ⇒
                renderHeaders(headers.toList, alwaysClose = ctx.requestMethod != HttpMethods.HEAD)
                renderEntityContentType(r, entity) ~~ CrLf
                Streamed(byteStrings(data))

              case HttpEntity.Chunked(contentType, chunks) ⇒
                if (ctx.requestProtocol == `HTTP/1.0`)
                  completeResponseRendering(HttpEntity.CloseDelimited(contentType, chunks.map(_.data)))
                else {
                  renderHeaders(headers.toList)
                  renderEntityContentType(r, entity) ~~ CrLf
                  Streamed(byteStrings(chunks.via(ChunkTransformer.flow)))
                }
            }

          renderStatusLine()
          completeResponseRendering(entity)
        }
      }

    sealed trait StrictOrStreamed
    case class Strict(bytes: ResponseRenderingOutput) extends StrictOrStreamed
    case class Streamed(source: Source[ResponseRenderingOutput, Any]) extends StrictOrStreamed
  }

  sealed trait CloseMode
  case object DontClose extends CloseMode
  case object CloseConnection extends CloseMode
  case class SwitchToWebSocket(handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]]) extends CloseMode
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
  private[http] case class SwitchToWebSocket(httpResponseBytes: ByteString, handler: Either[Graph[FlowShape[FrameEvent, FrameEvent], Any], Graph[FlowShape[Message, Message], Any]]) extends ResponseRenderingOutput
}
