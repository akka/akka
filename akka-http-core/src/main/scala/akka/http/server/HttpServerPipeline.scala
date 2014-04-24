/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import org.reactivestreams.api.Producer
import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.actor.ActorRefFactory
import akka.http.parsing.HttpRequestParser
import akka.http.rendering.HttpResponseRendererFactory
import akka.http.model.{ ErrorInfo, HttpRequest, HttpResponse }
import akka.http.Http
import akka.stream2.{ FanIn, Operation, Flow }
import akka.stream2.impl._
import HttpRequestParser._
import Operation.Split

private[http] class HttpServerPipeline(settings: ServerSettings, log: LoggingAdapter)(implicit refFactory: ActorRefFactory)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.chunklessStreaming)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    def splitParserOutput: ParserOutput ⇒ Split.Command = {
      case _: RequestStart ⇒ Split.First
      case _: EntityPart   ⇒ Split.Append
      case _: EntityChunk  ⇒ Split.Append
      case _: ParseError   ⇒ Split.First
    }

    def constructRequest(requestStart: RequestStart, entity: Producer[ParserOutput]): HttpRequest = ???

    val errorResponseBypass =
      Operation[(ParserOutput, Producer[HttpRequestParser.ParserOutput])]
        .map { case (ParseError(errorResponse), _) ⇒ Some(errorResponse); case _ ⇒ None }
        .toProcessor

    val requestStream =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .split(splitParserOutput)
        .headAndTail
        .tee(errorResponseBypass)
        .collect { case (x: RequestStart, entity) ⇒ constructRequest(x, entity) }
        .toProducer

    val responseStream =
      Operation[HttpResponse]
        .fanIn(errorResponseBypass, ErrorResponseBypassFanIn)
        .mapConcat(responseRendererFactory.newRenderer)
        .produceTo(tcpConn.outputStream)

    Http.IncomingConnection(tcpConn.remoteAddress, requestStream, responseStream)
  }
}

/**
 * a specialized fan-in which implements the following logic:
 * - when a response comes in on the primary channel it is buffered until a `None` is received from the secondary
 *   input (the error bypass), only then it is dispatched to downstream
 * - when a `None` is received from the secondary input it clears the buffered (or next) primary response
 *   for dispatch to downstream
 * - when a `Some` is received from the secondary input it is immediately dispatched to downstream, a potentially
 *   buffered regular response from the primary input is left untouched
 */
object ErrorResponseBypassFanIn extends FanIn.Provider[HttpResponse, Option[HttpResponse], HttpResponse] {
  def apply(primaryUpstream: Upstream, secondaryUpstream: Upstream, downstream: Downstream): ErrorResponseBypassFanIn =
    new ErrorResponseBypassFanIn(primaryUpstream, secondaryUpstream, downstream)
}

class ErrorResponseBypassFanIn(primaryUpstream: Upstream, secondaryUpstream: Upstream, downstream: Downstream)
  extends FanIn[HttpResponse, Option[HttpResponse], HttpResponse] {
  var requested = 0
  var primaryResponse: HttpResponse = _
  var primaryWriteThrough = false // if true the next primary element is to be directly dispatched to downstream
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
  def primaryOnNext(element: HttpResponse): Unit =
    if (!completed) {
      if (primaryWriteThrough) {
        primaryWriteThrough = false
        dispatch(element)
      } else primaryResponse = element
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

  def secondaryOnNext(element: Option[HttpResponse]): Unit =
    if (!completed)
      element match {
        case None =>
          if (primaryResponse ne null) {
            primaryResponse = null
            dispatch(primaryResponse)
          } else primaryWriteThrough = true
        case Some(errorResponse) => downstream.onNext(errorResponse)
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
  private def dispatch(response: HttpResponse): Unit = {
    downstream.onNext(response)
    requested -= 1
    if (requested > 0) requestNext()
  }
  private def requestNext(): Unit = {
    primaryUpstream.requestMore(1)
    secondaryUpstream.requestMore(1)
  }
}