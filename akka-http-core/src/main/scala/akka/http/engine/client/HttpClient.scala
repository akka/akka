/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.FlattenStrategy
import akka.stream.scaladsl._
import akka.http.model.{ HttpMethod, HttpRequest, HttpResponse }
import akka.http.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.engine.parsing.{ HttpHeaderParser, HttpResponseParser }
import akka.http.engine.parsing.ParserOutput._
import akka.http.util._

/**
 * INTERNAL API
 */
private[http] object HttpClient {

  def transportToConnectionClientFlow(transport: Flow[ByteString, ByteString],
                                      remoteAddress: InetSocketAddress,
                                      settings: ClientConnectionSettings,
                                      log: LoggingAdapter): Flow[HttpRequest, HttpResponse] = {
    import settings._

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpResponseParser(
      parserSettings,
      HttpHeaderParser(parserSettings) { errorInfo ⇒
        if (parserSettings.illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal response header").formatPretty)
      })

    val requestRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, log)
    val requestMethodByPass = new RequestMethodByPass(remoteAddress)

    Flow[HttpRequest]
      .map(requestMethodByPass)
      .transform("renderer", () ⇒ requestRendererFactory.newRenderer)
      .flatten(FlattenStrategy.concat)
      .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing request stream error"))
      .via(transport)
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
  }

  // FIXME: refactor to a pure-stream design that allows us to get rid of this ad-hoc queue here
  class RequestMethodByPass(serverAddress: InetSocketAddress)
    extends (HttpRequest ⇒ RequestRenderingContext) with (() ⇒ HttpMethod) {
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
}