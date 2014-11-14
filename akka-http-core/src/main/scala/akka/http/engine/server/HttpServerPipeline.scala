/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.stream.FlattenStrategy
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.http.engine.parsing.{ HttpHeaderParser, HttpRequestParser }
import akka.http.engine.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ HttpRequest, HttpResponse, HttpMethods }
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
      val bypassMerge = new BypassMerge

      val rootParsePipeline =
        Flow[ByteString]
          .transform("rootParser", () ⇒ requestParser)
          .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
          .headAndTail

      val rendererPipeline =
        Flow[ResponseRenderingContext]
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
      bypassFanout ~> bypass ~> bypassMerge.bypassInput
      userOut ~> bypassMerge.applicationInput ~> rendererPipeline ~> networkOut

    }.run()

    Http.IncomingConnection(tcpConn.remoteAddress, pipeline.get(userIn), pipeline.get(userOut))
  }

  // A special merge that works similarly to a combined `zip` + `map`
  // with the exception that certain elements on the bypass input of the `zip` (ParseErrors) cause
  // an immediate emitting of an element to downstream, without waiting for the applicationInput
  class BypassMerge extends FlexiMerge[ResponseRenderingContext]("BypassMerge") {
    import FlexiMerge._
    val bypassInput = createInputPort[MessageStart with RequestOutput]()
    val applicationInput = createInputPort[HttpResponse]()

    def createMergeLogic() = new MergeLogic[ResponseRenderingContext] {
      var requestStart: RequestStart = _

      override def inputHandles(inputCount: Int) = {
        require(inputCount == 2, s"BypassMerge must have two connected inputs, was $inputCount")
        Vector(bypassInput, applicationInput)
      }

      override def initialState = readBypass

      val readBypass = State[MessageStart with RequestOutput](Read(bypassInput)) {
        case (ctx, _, rs: RequestStart) ⇒
          requestStart = rs
          readApplicationInput

        case (ctx, _, ParseError(status, info)) ⇒
          log.warning("Illegal request, responding with status '{}': {}", status, info.formatPretty)
          val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
          ResponseRenderingContext(HttpResponse(status, entity = msg), closeAfterResponseCompletion = true)
          ctx.complete() // shouldn't this return a `State` rather than `Unit`?
          SameState // it seems weird to stay in the same state after completion
      }

      val readApplicationInput: State[HttpResponse] =
        State[HttpResponse](Read(applicationInput)) { (ctx, _, response) ⇒
          ctx.emit(ResponseRenderingContext(response, requestStart.method, requestStart.protocol,
            requestStart.closeAfterResponseCompletion))
          requestStart = null
          readBypass
        }

      override def initialCompletionHandling = eagerClose
    }
  }
}