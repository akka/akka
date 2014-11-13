/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.stream.FlattenStrategy
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.http.engine.parsing.{ HttpHeaderParser, HttpRequestParser }
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
  import settings.parserSettings

  // the initial header parser we initially use for every connection,
  // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
  val rootParser = new HttpRequestParser(
    parserSettings,
    settings.rawRequestUriHeader,
    HttpHeaderParser(parserSettings) { errorInfo ⇒
      if (parserSettings.illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)
    })

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.responseHeaderSizeHint, log)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    import FlowGraphImplicits._

    val networkIn = Source(tcpConn.inputStream)
    val networkOut = Sink(tcpConn.outputStream)

    val userIn = Sink.publisher[HttpRequest]
    val userOut = Source.subscriber[HttpResponse]

    // each connection uses a single (private) request parser instance for all its requests
    // which builds a cache of all header instances seen on that connection
    val requestParser = rootParser.createSharedCopy()

    val pipeline = FlowGraph { implicit b ⇒
      val bypassFanout = Broadcast[(RequestOutput, Source[RequestOutput])]("bypassFanout")
      val bypassFanin = Merge[Any]("merge")

      val rootParsePipeline =
        Flow[ByteString]
          .transform("rootParser", () ⇒ requestParser)
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
    new PushStage[Any, ResponseRenderingContext] {
      var applicationResponse: HttpResponse = _
      var requestStart: RequestStart = _

      override def onPush(elem: Any, ctx: Context[ResponseRenderingContext]): Directive = elem match {
        case response: HttpResponse ⇒
          requestStart match {
            case null ⇒
              applicationResponse = response
              ctx.pull()
            case x: RequestStart ⇒
              requestStart = null
              ctx.push(dispatch(x, response))
          }

        case requestStart: RequestStart ⇒
          applicationResponse match {
            case null ⇒
              this.requestStart = requestStart
              ctx.pull()
            case response ⇒
              applicationResponse = null
              ctx.push(dispatch(requestStart, response))
          }

        case ParseError(status, info) ⇒
          ctx.push(errorResponse(status, info))
      }

      def dispatch(requestStart: RequestStart, response: HttpResponse): ResponseRenderingContext = {
        import requestStart._
        ResponseRenderingContext(response, method, protocol, closeAfterResponseCompletion)
      }

      def errorResponse(status: StatusCode, info: ErrorInfo): ResponseRenderingContext = {
        log.warning("Illegal request, responding with status '{}': {}", status, info.formatPretty)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        ResponseRenderingContext(HttpResponse(status, entity = msg), closeAfterResponseCompletion = true)
      }
    }
}
