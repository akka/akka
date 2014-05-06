/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import org.reactivestreams.api.Producer
import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.actor.ActorRefFactory
import akka.http.parsing.HttpRequestParser
import akka.http.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ StatusCode, ErrorInfo, HttpRequest, HttpResponse }
import akka.http.parsing.ParserOutput._
import akka.http.Http
import akka.stream2.{ FanIn, Operation, Flow }
import akka.stream2.impl._
import Operation.Split

private[http] class HttpServerPipeline(settings: ServerSettings, log: LoggingAdapter)(implicit refFactory: ActorRefFactory)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.chunklessStreaming,
    settings.responseHeaderSizeHint, log)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    import refFactory.dispatcher

    val applicationBypass =
      Operation[(RequestOutput, Producer[RequestOutput])]
        .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }
        .toProcessor

    val requestStream =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .split(HttpServerPipeline.splitParserOutput)
        .headAndTail
        .tee(applicationBypass)
        .collect { case (x: RequestStart, entityParts) ⇒ HttpServerPipeline.constructRequest(x, entityParts) }
        .toProducer

    val responseStream =
      Operation[HttpResponse]
        .fanIn(applicationBypass, ApplicationBypassFanIn)
        .transform(responseRendererFactory.newRenderer)
        .concatAll
        .produceTo(tcpConn.outputStream)

    Http.IncomingConnection(tcpConn.remoteAddress, requestStream, responseStream)
  }

  /**
   * A FanIn which combines the HttpResponse coming in from the application with the ParserOutput.RequestStart
   * produced by the request parser into a ResponseRenderingContext.
   * If the parser produced a ParserOutput.ParseError the error response is immediately dispatched to downstream.
   */
  object ApplicationBypassFanIn extends FanIn.Provider[HttpResponse, MessageStart with RequestOutput, ResponseRenderingContext] {
    def apply(primaryUpstream: Upstream, secondaryUpstream: Upstream, downstream: Downstream): ApplicationBypassFanIn =
      new ApplicationBypassFanIn(primaryUpstream, secondaryUpstream, downstream)
  }

  class ApplicationBypassFanIn(primaryUpstream: Upstream, secondaryUpstream: Upstream, downstream: Downstream)
    extends FanIn[HttpResponse, MessageStart with RequestOutput, ResponseRenderingContext] {
    var requested = 0
    var applicationResponse: HttpResponse = _
    var requestStart: RequestStart = _
    var completed = false

    def requestMore(elements: Int): Unit =
      if (!completed) {
        requested += elements
        if (requested == elements) requestNext()
      }

    def cancel(): Unit =
      if (!completed) {
        completed = true
        primaryUpstream.cancel()
        secondaryUpstream.cancel()
      }

    def primaryOnNext(response: HttpResponse): Unit =
      if (!completed) {
        requestStart match {
          case null ⇒ applicationResponse = response
          case x: RequestStart ⇒
            requestStart = null
            dispatch(x, response)
        }
      }

    def primaryOnComplete(): Unit =
      if (!completed) {
        completed = true
        downstream.onComplete()
      }

    def primaryOnError(cause: Throwable): Unit =
      if (!completed) {
        completed = true
        downstream.onError(cause)
      }

    def secondaryOnNext(element: MessageStart with RequestOutput): Unit =
      if (!completed)
        element match {
          case x: RequestStart ⇒
            if (applicationResponse ne null) {
              applicationResponse = null
              dispatch(x, applicationResponse)
            } else requestStart = x
          case ParseError(status, info) ⇒
            downstream.onNext(errorResponse(status, info))
            cancel()
        }

    def secondaryOnComplete(): Unit =
      if (!completed) {
        completed = true
        downstream.onComplete()
      }

    def secondaryOnError(cause: Throwable): Unit =
      if (!completed) {
        completed = true
        downstream.onError(cause)
      }

    private def dispatch(requestStart: RequestStart, response: HttpResponse): Unit = {
      import requestStart._
      downstream.onNext(ResponseRenderingContext(response, method, protocol, closeAfterResponseCompletion))
      requested -= 1
      if (requested > 0) requestNext()
    }

    private def requestNext(): Unit = {
      primaryUpstream.requestMore(1)
      secondaryUpstream.requestMore(1)
    }

    private def errorResponse(status: StatusCode, info: ErrorInfo): ResponseRenderingContext = {
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