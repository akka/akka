/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import akka.stream.{ FlattenStrategy, FlowMaterializer }
import akka.event.LoggingAdapter
import akka.stream.io.StreamTcp
import akka.stream.scaladsl.{ Flow, Duct }
import akka.http.Http
import akka.http.model.{ HttpMethod, HttpRequest, ErrorInfo, HttpResponse }
import akka.http.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.parsing.HttpResponseParser
import akka.http.parsing.ParserOutput._
import akka.http.util._

/**
 * INTERNAL API
 */
private[http] class HttpClientPipeline(effectiveSettings: ClientConnectionSettings,
                                       materializer: FlowMaterializer,
                                       log: LoggingAdapter)
  extends (StreamTcp.OutgoingTcpConnection ⇒ Http.OutgoingConnection) {

  import effectiveSettings._

  val rootParser = new HttpResponseParser(parserSettings, materializer)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal response header").formatPretty)

  val responseRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, materializer, log)

  def apply(tcpConn: StreamTcp.OutgoingTcpConnection): Http.OutgoingConnection = {
    val requestMethodByPass = new RequestMethodByPass(tcpConn.remoteAddress)

    val (contextBypassConsumer, contextBypassProducer) =
      Duct[(HttpRequest, Any)].map(_._2).build(materializer)

    val requestConsumer =
      Duct[(HttpRequest, Any)]
        .tee(contextBypassConsumer)
        .map(requestMethodByPass)
        .transform(responseRendererFactory.newRenderer)
        .flatten(FlattenStrategy.concat)
        .transform(errorLogger(log, "Outgoing request stream error"))
        .produceTo(materializer, tcpConn.outputStream)

    val responseProducer =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader, requestMethodByPass))
        .splitWhen(_.isInstanceOf[MessageStart])
        .headAndTail(materializer)
        .collect {
          case (ResponseStart(statusCode, protocol, headers, createEntity, _), entityParts) ⇒
            HttpResponse(statusCode, headers, createEntity(entityParts), protocol)
        }
        .zip(contextBypassProducer)
        .toProducer(materializer)

    val processor = HttpClientProcessor(requestConsumer.getSubscriber, responseProducer.getPublisher)
    Http.OutgoingConnection(tcpConn.remoteAddress, tcpConn.localAddress, processor)
  }

  class RequestMethodByPass(serverAddress: InetSocketAddress)
    extends (((HttpRequest, Any)) ⇒ RequestRenderingContext) with (() ⇒ HttpMethod) {
    private[this] var requestMethods = Queue.empty[HttpMethod]
    def apply(tuple: (HttpRequest, Any)) = {
      val request = tuple._1
      requestMethods = requestMethods.enqueue(request.method)
      RequestRenderingContext(request, serverAddress)
    }
    def apply(): HttpMethod =
      if (requestMethods.nonEmpty) {
        val method = requestMethods.head
        requestMethods = requestMethods.tail
        method
      } else HttpResponseParser.NoMethod
  }
}