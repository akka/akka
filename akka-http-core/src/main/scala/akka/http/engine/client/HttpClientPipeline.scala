/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import akka.stream.scaladsl._
import akka.event.LoggingAdapter
import akka.stream.FlattenStrategy
import akka.stream.io.StreamTcp
import akka.util.ByteString
import akka.http.Http
import akka.http.model.{ HttpMethod, HttpRequest, ErrorInfo, HttpResponse }
import akka.http.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.engine.parsing.{ HttpRequestParser, HttpHeaderParser, HttpResponseParser }
import akka.http.engine.parsing.ParserOutput._
import akka.http.util._

import scala.concurrent.{ ExecutionContext, Future }

/**
 * INTERNAL API
 */
private[http] class HttpClientPipeline(effectiveSettings: ClientConnectionSettings,
                                       log: LoggingAdapter)(implicit ec: ExecutionContext)
  extends ((StreamTcp.OutgoingTcpFlow, InetSocketAddress) ⇒ Http.OutgoingFlow) {

  import effectiveSettings._

  // the initial header parser we initially use for every connection,
  // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
  val rootParser = new HttpResponseParser(
    parserSettings,
    HttpHeaderParser(parserSettings) { errorInfo ⇒
      if (parserSettings.illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal response header").formatPretty)
    })

  val requestRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, log)

  def apply(tcpFlow: StreamTcp.OutgoingTcpFlow, remoteAddress: InetSocketAddress): Http.OutgoingFlow = {
    import FlowGraphImplicits._

    val httpKey = new HttpKey(tcpFlow.key)

    val flowWithHttpKey = tcpFlow.flow.withKey(httpKey)

    val requestMethodByPass = new RequestMethodByPass(remoteAddress)

    val pipeline = Flow() { implicit b ⇒
      val userIn = UndefinedSource[(HttpRequest, Any)]
      val userOut = UndefinedSink[(HttpResponse, Any)]

      val bypassFanout = Unzip[HttpRequest, Any]("bypassFanout")
      val bypassFanin = Zip[HttpResponse, Any]("bypassFanin")

      val requestPipeline =
        Flow[HttpRequest]
          .map(requestMethodByPass)
          .transform("renderer", () ⇒ requestRendererFactory.newRenderer)
          .flatten(FlattenStrategy.concat)
          .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing request stream error"))

      val responsePipeline =
        Flow[ByteString]
          .transform("rootParser", () ⇒
            // each connection uses a single (private) response parser instance for all its responses
            // which builds a cache of all header instances seen on that connection
            rootParser.createShallowCopy(requestMethodByPass))
          .splitWhen(_.isInstanceOf[MessageStart])
          .headAndTail
          .collect {
            case (ResponseStart(statusCode, protocol, headers, createEntity, _), entityParts) ⇒
              HttpResponse(statusCode, headers, createEntity(entityParts), protocol)
          }

      //FIXME: the graph is unnecessary after fixing #15957
      userIn ~> bypassFanout.in
      bypassFanout.left ~> requestPipeline ~> flowWithHttpKey ~> responsePipeline ~> bypassFanin.left
      bypassFanout.right ~> bypassFanin.right
      bypassFanin.out ~> userOut

      userIn -> userOut
    }

    Http.OutgoingFlow(pipeline, httpKey)
  }

  class RequestMethodByPass(serverAddress: InetSocketAddress)
    extends ((HttpRequest) ⇒ RequestRenderingContext) with (() ⇒ HttpMethod) {
    private[this] var requestMethods = Queue.empty[HttpMethod]
    def apply(request: HttpRequest) = {
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

  class HttpKey(tcpKey: Key { type MaterializedType = Future[StreamTcp.OutgoingTcpConnection] }) extends Key {
    type MaterializedType = Future[Http.OutgoingConnection]

    override def materialize(map: MaterializedMap): MaterializedType =
      map.get(tcpKey).map(tcp ⇒ Http.OutgoingConnection(tcp.remoteAddress, tcp.localAddress))
  }
}
