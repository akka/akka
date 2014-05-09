/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import org.reactivestreams.api.Producer
import scala.concurrent.ExecutionContext
import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.http.parsing.HttpRequestParser
import akka.http.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ StatusCode, ErrorInfo, HttpRequest, HttpResponse }
import akka.http.parsing.ParserOutput._
import akka.http.Http
import waves.{ Operation, Flow }
import Operation.Split

private[http] class HttpServerPipeline(settings: ServerSettings, log: LoggingAdapter)(implicit ec: ExecutionContext)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.chunklessStreaming,
    settings.responseHeaderSizeHint, log)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    val applicationBypass =
      Operation[(RequestOutput, Producer[RequestOutput])]
        .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }
        .toProcessor

    val requestProducer =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .split(HttpServerPipeline.splitParserOutput)
        .headAndTail
        .tee(_ produceTo applicationBypass)
        .collect { case (x: RequestStart, entityParts) ⇒ HttpServerPipeline.constructRequest(x, entityParts) }
        .toProducer

    val responseConsumer =
      Operation[HttpResponse]
        .merge(applicationBypass)
        .transform(applyApplicationBypass)
        .transform(responseRendererFactory.newRenderer)
        .concatAll
        .onError(e ⇒ log.error(e, "Response stream error"))
        .produceTo(tcpConn.outputStream)

    Http.IncomingConnection(tcpConn.remoteAddress, requestProducer, responseConsumer)
  }

  /**
   * Combines the HttpResponse coming in from the application with the ParserOutput.RequestStart
   * produced by the request parser into a ResponseRenderingContext.
   * If the parser produced a ParserOutput.ParseError the error response is immediately dispatched to downstream.
   */
  def applyApplicationBypass =
    new Operation.Transformer[Any, ResponseRenderingContext] {
      var applicationResponse: HttpResponse = _
      var requestStart: RequestStart = _

      def onNext(elem: Any): Seq[ResponseRenderingContext] = elem match {
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

      def dispatch(requestStart: RequestStart, response: HttpResponse): Seq[ResponseRenderingContext] = {
        import requestStart._
        ResponseRenderingContext(response, method, protocol, closeAfterResponseCompletion) :: Nil
      }

      def errorResponse(status: StatusCode, info: ErrorInfo): ResponseRenderingContext = {
        log.warning("Illegal request, responding with status '{}': {}", status, info.formatPretty)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        ResponseRenderingContext(HttpResponse(status, msg), closeAfterResponseCompletion = true)
      }
    }
}

private[http] object HttpServerPipeline {
  val splitParserOutput: RequestOutput ⇒ Split.Command = {
    case _: MessageStart ⇒ Split.First
    case _               ⇒ Split.Append
  }

  def constructRequest(requestStart: RequestStart, entityParts: Producer[RequestOutput]): HttpRequest = {
    import requestStart._
    HttpRequest(method, uri, headers, createEntity(entityParts), protocol)
  }
}