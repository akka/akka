/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.scaladsl.model.ws._

import scala.concurrent.{ Future, Promise }

import akka.util.ByteString
import akka.event.LoggingAdapter

import akka.stream.stage._
import akka.stream.BidiShape
import akka.stream.io.{ SessionBytes, SendBytes, SslTlsInbound }
import akka.stream.scaladsl._

import akka.http.ClientConnectionSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpResponse, HttpMethods }
import akka.http.scaladsl.model.headers.Host

import akka.http.impl.engine.parsing.HttpMessageParser.StateResult
import akka.http.impl.engine.parsing.ParserOutput.{ RemainingBytes, ResponseStart, NeedMoreData }
import akka.http.impl.engine.parsing.{ ParserOutput, HttpHeaderParser, HttpResponseParser }
import akka.http.impl.engine.rendering.{ HttpRequestRendererFactory, RequestRenderingContext }
import akka.http.impl.engine.ws.Handshake.Client.NegotiatedWebsocketSettings
import akka.http.impl.util.StreamUtils

object WebsocketClientBlueprint {
  /**
   * Returns a WebsocketClientLayer that can be materialized once.
   */
  def apply(request: WebsocketRequest,
            settings: ClientConnectionSettings,
            log: LoggingAdapter): Http.WebsocketClientLayer =
    (simpleTls.atopMat(handshake(request, settings, log))(Keep.right) atop
      Websocket.framing atop
      Websocket.stack(serverSide = false, maskingRandomFactory = settings.websocketRandomFactory, log = log)).reversed

  /**
   * A bidi flow that injects and inspects the WS handshake and then goes out of the way. This BidiFlow
   * can only be materialized once.
   */
  def handshake(request: WebsocketRequest,
                settings: ClientConnectionSettings,
                log: LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, Future[WebsocketUpgradeResponse]] = {
    import request._
    val result = Promise[WebsocketUpgradeResponse]()

    val valve = StreamUtils.OneTimeValve()

    val (initialRequest, key) = Handshake.Client.buildRequest(uri, extraHeaders, subprotocol.toList, settings.websocketRandomFactory())
    val hostHeader = Host(uri.authority)
    val renderedInitialRequest =
      HttpRequestRendererFactory.renderStrict(RequestRenderingContext(initialRequest, hostHeader), settings, log)

    class UpgradeStage extends StatefulStage[ByteString, ByteString] {
      type State = StageState[ByteString, ByteString]

      def initial: State = parsingResponse

      def parsingResponse: State = new State {
        // a special version of the parser which only parses one message and then reports the remaining data
        // if some is available
        val parser = new HttpResponseParser(settings.parserSettings, HttpHeaderParser(settings.parserSettings)()) {
          var first = true
          override protected def parseMessage(input: ByteString, offset: Int): StateResult = {
            if (first) {
              first = false
              super.parseMessage(input, offset)
            } else {
              emit(RemainingBytes(input.drop(offset)))
              terminate()
            }
          }
        }
        parser.setRequestMethodForNextResponse(HttpMethods.GET)

        def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = {
          parser.onPush(elem) match {
            case NeedMoreData ⇒ ctx.pull()
            case ResponseStart(status, protocol, headers, entity, close) ⇒
              val response = HttpResponse(status, headers, protocol = protocol)
              Handshake.Client.validateResponse(response, subprotocol.toList, key) match {
                case Right(NegotiatedWebsocketSettings(protocol)) ⇒
                  result.success(ValidUpgrade(response, protocol))

                  become(transparent)
                  valve.open()

                  val parseResult = parser.onPull()
                  require(parseResult == ParserOutput.MessageEnd, s"parseResult should be MessageEnd but was $parseResult")
                  parser.onPull() match {
                    case NeedMoreData          ⇒ ctx.pull()
                    case RemainingBytes(bytes) ⇒ ctx.push(bytes)
                  }
                case Left(problem) ⇒
                  result.success(InvalidUpgradeResponse(response, s"Websocket server at $uri returned $problem"))
                  ctx.fail(throw new IllegalArgumentException(s"Websocket upgrade did not finish because of '$problem'"))
              }
          }
        }
      }

      def transparent: State = new State {
        def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = ctx.push(elem)
      }
    }

    BidiFlow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val networkIn = b.add(Flow[ByteString].transform(() ⇒ new UpgradeStage))
      val wsIn = b.add(Flow[ByteString])

      val handshakeRequestSource = b.add(Source.single(renderedInitialRequest) ++ valve.source)
      val httpRequestBytesAndThenWSBytes = b.add(Concat[ByteString]())

      handshakeRequestSource ~> httpRequestBytesAndThenWSBytes
      wsIn.outlet ~> httpRequestBytesAndThenWSBytes

      BidiShape(
        networkIn.in,
        networkIn.out,
        wsIn.in,
        httpRequestBytesAndThenWSBytes.out)
    }) mapMaterializedValue (_ ⇒ result.future)
  }

  def simpleTls: BidiFlow[SslTlsInbound, ByteString, ByteString, SendBytes, Unit] =
    BidiFlow.fromFlowsMat(
      Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) ⇒ bytes },
      Flow[ByteString].map(SendBytes))(Keep.none)
}
