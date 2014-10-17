/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.stream.Transformer
import akka.stream.scaladsl2._
import akka.http.engine.parsing.HttpRequestParser
import akka.http.engine.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ StatusCode, ErrorInfo, HttpRequest, HttpResponse, HttpMethods }
import akka.http.engine.parsing.ParserOutput._
import akka.http.Http
import akka.http.util._
import akka.util.ByteString

/**
 * INTERNAL API
 */
private[http] class HttpServerPipeline(settings: ServerSettings, log: LoggingAdapter)(implicit fm: FlowMaterializer)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.responseHeaderSizeHint, log)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    import FlowGraphImplicits._

    val networkIn = Source(tcpConn.inputStream)
    val networkOut = Sink(tcpConn.outputStream)

    val userIn = Sink.publisher[HttpRequest]
    val userOut = Source.subscriber[HttpResponse]

    val pipeline = FlowGraph { implicit b ⇒
      val bypassFanout = Broadcast[(RequestOutput, Source[RequestOutput])]("bypassFanout")
      val bypassFanin = Merge[Any]("merge")

      val rootParsePipeline =
        Flow[ByteString]
          .transform("rootParser", () ⇒ rootParser.copyWith(warnOnIllegalHeader))
          .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
          .headAndTail

      val rendererPipeline =
        Flow[Any]
          .transform("applyApplicationBypass", () ⇒ applyApplicationBypass)
          .transform("renderer", () ⇒ responseRendererFactory.newRenderer)
          .flatten(FlattenStrategy.concat)
          .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing response stream error"))

      val requestTweaking = Flow[(RequestOutput, Source[RequestOutput])].collect {
        case (RequestStart(method, uri, protocol, headers, createEntity, _), entityParts) ⇒
          val effectiveUri = HttpRequest.effectiveUri(uri, headers, securedConnection = false, settings.defaultHostHeader)
          val effectiveMethod = if (method == HttpMethods.HEAD && settings.transparentHeadRequests) HttpMethods.GET else method
          HttpRequest(effectiveMethod, effectiveUri, headers, createEntity(entityParts), protocol)
      }

      val bypass =
        Flow[(RequestOutput, Source[RequestOutput])]
          .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }

      //FIXME: the graph is unnecessary after fixing #15957
      networkIn ~> rootParsePipeline ~> bypassFanout ~> requestTweaking ~> userIn
      bypassFanout ~> bypass ~> bypassFanin
      userOut ~> bypassFanin ~> rendererPipeline ~> networkOut

    }.run()

    Http.IncomingConnection(tcpConn.remoteAddress, pipeline.get(userIn), pipeline.get(userOut))
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