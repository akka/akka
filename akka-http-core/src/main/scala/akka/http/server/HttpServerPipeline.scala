/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.actor.ActorRefFactory
import akka.http.model.{ HttpResponse, ErrorInfo }
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
    val requestStream =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .toProducer

    val responseStream =
      Operation[HttpResponse]
        .mapConcat(responseRendererFactory.newRenderer)
        .produceTo(tcpConn.outputStream)

    Http.IncomingConnection(tcpConn.remoteAddress, requestStream, responseStream)
  }
}
