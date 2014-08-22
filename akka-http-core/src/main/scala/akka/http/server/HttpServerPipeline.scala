/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import org.reactivestreams.Publisher
import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.stream.{ FlattenStrategy, Transformer, FlowMaterializer }
import akka.stream.scaladsl.{ Flow, Duct }
import akka.http.parsing.HttpRequestParser
import akka.http.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ StatusCode, ErrorInfo, HttpRequest, HttpResponse }
import akka.http.parsing.ParserOutput._
import akka.http.Http
import akka.http.util._

/**
 * INTERNAL API
 */
private[http] class HttpServerPipeline(settings: ServerSettings,
                                       materializer: FlowMaterializer,
                                       log: LoggingAdapter)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader, materializer)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader,
    settings.responseHeaderSizeHint, materializer, log)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    val (applicationBypassSubscriber, applicationBypassPublisher) =
      Duct[(RequestOutput, Publisher[RequestOutput])]
        .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }
        .build()(materializer)

    val requestPublisher =
      Flow(tcpConn.inputStream)
        .transform("rootParser", () ⇒ rootParser.copyWith(warnOnIllegalHeader))
        // this will create extra single element `[MessageEnd]` substreams, that will
        // be filtered out by the above `collect` for the applicationBypass and the
        // below `collect` for the actual request handling
        // TODO: replace by better combinator, maybe `splitAfter(_ == MessageEnd)`?
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
        .headAndTail(materializer)
        .broadcast(applicationBypassSubscriber)
        .collect {
          case (RequestStart(method, uri, protocol, headers, createEntity, _), entityParts) ⇒
            val effectiveUri = HttpRequest.effectiveUri(uri, headers, securedConnection = false, settings.defaultHostHeader)
            HttpRequest(method, effectiveUri, headers, createEntity(entityParts), protocol)
        }
        .toPublisher()(materializer)

    val responseSubscriber =
      Duct[HttpResponse]
        .merge(applicationBypassPublisher)
        .transform("applyApplicationBypass", () ⇒ applyApplicationBypass)
        .transform("renderer", () ⇒ responseRendererFactory.newRenderer)
        .flatten(FlattenStrategy.concat)
        .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing response stream error"))
        .produceTo(tcpConn.outputStream)(materializer)

    Http.IncomingConnection(tcpConn.remoteAddress, requestPublisher, responseSubscriber)
  }

  /**
   * Combines the HttpResponse coming in from the application with the ParserOutput.RequestStart
   * produced by the request parser into a ResponseRenderingContext.
   * If the parser produced a ParserOutput.ParseError the error response is immediately dispatched to downstream.
   */
  def applyApplicationBypass =
    new Transformer[Any, ResponseRenderingContext] {
      var applicationResponse: HttpResponse = _
      var requestStart: RequestStart = _

      def onNext(elem: Any) = elem match {
        case response: HttpResponse ⇒
          requestStart match {
            case null ⇒
              applicationResponse = response
              Nil
            case x: RequestStart ⇒
              requestStart = null
              dispatch(x, response)
          }

        case requestStart: RequestStart ⇒
          applicationResponse match {
            case null ⇒
              this.requestStart = requestStart
              Nil
            case response ⇒
              applicationResponse = null
              dispatch(requestStart, response)
          }

        case ParseError(status, info) ⇒ errorResponse(status, info) :: Nil
      }

      def dispatch(requestStart: RequestStart, response: HttpResponse): List[ResponseRenderingContext] = {
        import requestStart._
        ResponseRenderingContext(response, method, protocol, closeAfterResponseCompletion) :: Nil
      }

      def errorResponse(status: StatusCode, info: ErrorInfo): ResponseRenderingContext = {
        log.warning("Illegal request, responding with status '{}': {}", status, info.formatPretty)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        ResponseRenderingContext(HttpResponse(status, entity = msg), closeAfterResponseCompletion = true)
      }
    }
}