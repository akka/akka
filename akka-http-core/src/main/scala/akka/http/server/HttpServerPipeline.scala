/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.actor.ActorRefFactory
import akka.http.model.{ HttpRequest, HttpResponse, ErrorInfo }
import akka.http.parsing.HttpRequestParser
import akka.http.rendering.HttpResponseRendererFactory
import akka.http.Http
import akka.stream2.{ Operation, Flow }

private[http] class HttpServerPipeline(settings: ServerSettings, log: LoggingAdapter)(implicit refFactory: ActorRefFactory)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.chunklessStreaming)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    val errorResponseBypass =
      Operation[Either[HttpResponse, HttpRequest]]
        .map(_.left.toOption)
        .toProcessor

    val requestStream =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .tee(errorResponseBypass)
        .collect { case Right(request) ⇒ request }
        .toProducer

    val responseStream =
      Operation[HttpResponse]
        .fanIn(errorResponseBypass, new ErrorResponseBypassFanIn)
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
private[server] class ErrorResponseBypassFanIn extends Operation.FanIn[HttpResponse, Option[HttpResponse], HttpResponse] {
  def next(): immutable.Seq[HttpResponse] = ???
  def primaryDemand: Int = ???
  def secondaryDemand: Int = ???
  def primaryOnNext(elem: HttpResponse): Unit = ???
  def secondaryOnNext(elem: Option[HttpResponse]): Unit = ???
  def primaryOnComplete(): Unit = ???
  def secondaryOnComplete(): Unit = ???
}