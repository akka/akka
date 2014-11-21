/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import akka.util.ByteString

import scala.collection.immutable.Queue
import akka.stream.scaladsl._
import akka.event.LoggingAdapter
import akka.stream.FlowMaterializer
import akka.stream.FlattenStrategy
import akka.stream.io.StreamTcp
import akka.http.Http
import akka.http.model.{ HttpMethod, HttpRequest, ErrorInfo, HttpResponse }
import akka.http.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.engine.parsing.HttpResponseParser
import akka.http.engine.parsing.ParserOutput._
import akka.http.util._

/**
 * INTERNAL API
 */
private[http] class HttpClientPipeline(effectiveSettings: ClientConnectionSettings,
                                       log: LoggingAdapter)(implicit fm: FlowMaterializer)
  extends (StreamTcp.OutgoingTcpConnection ⇒ Http.OutgoingConnection) {

  import effectiveSettings._

  val rootParser = new HttpResponseParser(parserSettings)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal response header").formatPretty)

  val requestRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, log)

  def apply(tcpConn: StreamTcp.OutgoingTcpConnection): Http.OutgoingConnection = {
    import FlowGraphImplicits._

    val requestMethodByPass = new RequestMethodByPass(tcpConn.remoteAddress)

    val userIn = Source.subscriber[(HttpRequest, Any)]
    val userOut = Sink.publisher[(HttpResponse, Any)]

    val netOut = Sink(tcpConn.outputStream)
    val netIn = Source(tcpConn.inputStream)

    val pipeline = FlowGraph { implicit b ⇒
      val bypassFanout = Broadcast[(HttpRequest, Any)]("bypassFanout")
      val bypassFanin = Zip[HttpResponse, Any]("bypassFanin")

      val requestPipeline =
        Flow[(HttpRequest, Any)]
          .map(requestMethodByPass)
          .transform("renderer", () ⇒ requestRendererFactory.newRenderer)
          .flatten(FlattenStrategy.concat)
          .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing request stream error"))

      val responsePipeline =
        Flow[ByteString]
          .transform("rootParser", () ⇒ rootParser.copyWith(warnOnIllegalHeader, requestMethodByPass))
          .splitWhen(_.isInstanceOf[MessageStart])
          .headAndTail
          .collect {
            case (ResponseStart(statusCode, protocol, headers, createEntity, _), entityParts) ⇒
              HttpResponse(statusCode, headers, createEntity(entityParts), protocol)
          }

      //FIXME: the graph is unnecessary after fixing #15957
      userIn ~> bypassFanout ~> requestPipeline ~> netOut
      bypassFanout ~> Flow[(HttpRequest, Any)].map(_._2) ~> bypassFanin.right
      netIn ~> responsePipeline ~> bypassFanin.left
      bypassFanin.out ~> userOut
    }.run()

    Http.OutgoingConnection(
      tcpConn.remoteAddress,
      tcpConn.localAddress,
      pipeline.get(userOut),
      pipeline.get(userIn))
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
