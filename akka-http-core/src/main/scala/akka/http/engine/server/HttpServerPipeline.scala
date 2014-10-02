/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import akka.stream.scaladsl2._
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.io.StreamTcp
import akka.stream.Transformer
import akka.http.engine.parsing.HttpRequestParser
import akka.http.engine.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ StatusCode, ErrorInfo, HttpRequest, HttpResponse, HttpMethods }
import akka.http.engine.parsing.ParserOutput._
import akka.http.Http
import akka.http.util._

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

    val networkIn = PublisherSource(tcpConn.inputStream)
    val networkOut = SubscriberSink(tcpConn.outputStream)

    val userIn = PublisherSink[HttpRequest]
    val userOut = SubscriberSource[HttpResponse]

    val pipeline = FlowGraph { implicit b ⇒
      val bypassFanout = Broadcast[(RequestOutput, FlowWithSource[RequestOutput, RequestOutput])]("bypassFanout")
      val bypassFanin = Merge[Any]("merge")

      val rootParsePipeline =
        FlowFrom[ByteString]
          .transform("rootParser", () ⇒ rootParser.copyWith(warnOnIllegalHeader))
          .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
          .headAndTail

      val rendererPipeline =
        FlowFrom[Any]
          .transform("applyApplicationBypass", () ⇒ applyApplicationBypass)
          .transform("renderer", () ⇒ responseRendererFactory.newRenderer)
          .flatten(FlattenStrategy.concat)
          .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing response stream error"))

      val x = FlowFrom[(RequestOutput, FlowWithSource[RequestOutput, RequestOutput])].collect {
        case (RequestStart(method, uri, protocol, headers, createEntity, _), entityParts) ⇒
          val effectiveUri = HttpRequest.effectiveUri(uri, headers, securedConnection = false, settings.defaultHostHeader)
          val effectiveMethod = if (method == HttpMethods.HEAD && settings.transparentHeadRequests) HttpMethods.GET else method
          HttpRequest(effectiveMethod, effectiveUri, headers, createEntity(entityParts), protocol)
      }

      val bypass =
        FlowFrom[(RequestOutput, FlowWithSource[RequestOutput, RequestOutput])]
          .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }

      networkIn ~> rootParsePipeline ~> bypassFanout ~> x ~> userIn
      bypassFanout ~> bypass ~> bypassFanin.asInstanceOf[JunctionInPort[MessageStart with RequestOutput]]
      userOut ~> bypassFanin.asInstanceOf[JunctionInPort[HttpResponse]] ~> rendererPipeline ~> networkOut

    }.run()

    Http.IncomingConnection(tcpConn.remoteAddress, userIn.publisher(pipeline), userOut.subscriber(pipeline))
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