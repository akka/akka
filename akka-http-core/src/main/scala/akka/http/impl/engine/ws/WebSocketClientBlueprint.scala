/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.http.scaladsl.model.ws._

import scala.concurrent.{ Future, Promise }
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.stage._
import akka.stream._
import akka.stream.TLSProtocol._
import akka.stream.scaladsl._
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpMethods, HttpResponse }
import akka.http.scaladsl.model.headers.Host
import akka.http.impl.engine.parsing.HttpMessageParser.StateResult
import akka.http.impl.engine.parsing.ParserOutput.{ NeedMoreData, RemainingBytes, ResponseStart }
import akka.http.impl.engine.parsing.{ HttpHeaderParser, HttpResponseParser, ParserOutput }
import akka.http.impl.engine.rendering.{ HttpRequestRendererFactory, RequestRenderingContext }
import akka.http.impl.engine.ws.Handshake.Client.NegotiatedWebSocketSettings
import akka.http.impl.util.StreamUtils
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage

object WebSocketClientBlueprint {
  /**
   * Returns a WebSocketClientLayer that can be materialized once.
   */
  def apply(
    request:  WebSocketRequest,
    settings: ClientConnectionSettings,
    log:      LoggingAdapter): Http.WebSocketClientLayer =
    (simpleTls.atopMat(handshake(request, settings, log))(Keep.right) atop
      WebSocket.framing atop
      WebSocket.stack(serverSide = false, maskingRandomFactory = settings.websocketRandomFactory, log = log)).reversed

  /**
   * A bidi flow that injects and inspects the WS handshake and then goes out of the way. This BidiFlow
   * can only be materialized once.
   */
  def handshake(
    request:  WebSocketRequest,
    settings: ClientConnectionSettings,
    log:      LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, Future[WebSocketUpgradeResponse]] = {
    import request._
    val result = Promise[WebSocketUpgradeResponse]()

    val valve = StreamUtils.OneTimeValve()

    val (initialRequest, key) = Handshake.Client.buildRequest(uri, extraHeaders, subprotocol.toList, settings.websocketRandomFactory())
    val hostHeader = Host(uri.authority.normalizedFor(uri.scheme))
    val renderedInitialRequest =
      HttpRequestRendererFactory.renderStrict(RequestRenderingContext(initialRequest, hostHeader), settings, log)

    class UpgradeStage extends SimpleLinearGraphStage[ByteString] {

      override def createLogic(attributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) with InHandler with OutHandler {
          // a special version of the parser which only parses one message and then reports the remaining data
          // if some is available
          val parser = new HttpResponseParser(settings.parserSettings, HttpHeaderParser(settings.parserSettings)()) {
            var first = true
            override def handleInformationalResponses = false
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
          parser.setContextForNextResponse(HttpResponseParser.ResponseContext(HttpMethods.GET, None))

          override def onPush(): Unit = {
            parser.parseBytes(grab(in)) match {
              case NeedMoreData ⇒ pull(in)
              case ResponseStart(status, protocol, headers, entity, close) ⇒
                val response = HttpResponse(status, headers, protocol = protocol)
                Handshake.Client.validateResponse(response, subprotocol.toList, key) match {
                  case Right(NegotiatedWebSocketSettings(protocol)) ⇒
                    result.success(ValidUpgrade(response, protocol))

                    setHandler(in, new InHandler {
                      override def onPush(): Unit = push(out, grab(in))
                    })
                    valve.open()

                    val parseResult = parser.onPull()
                    require(parseResult == ParserOutput.MessageEnd, s"parseResult should be MessageEnd but was $parseResult")
                    parser.onPull() match {
                      case NeedMoreData          ⇒ pull(in)
                      case RemainingBytes(bytes) ⇒ push(out, bytes)
                      case other ⇒
                        throw new IllegalStateException(s"unexpected element of type ${other.getClass}")
                    }
                  case Left(problem) ⇒
                    result.success(InvalidUpgradeResponse(response, s"WebSocket server at $uri returned $problem"))
                    failStage(new IllegalArgumentException(s"WebSocket upgrade did not finish because of '$problem'"))
                }
              case other ⇒
                throw new IllegalStateException(s"unexpected element of type ${other.getClass}")
            }
          }

          override def onPull(): Unit = pull(in)

          setHandlers(in, out, this)
        }

      override def toString = "UpgradeStage"
    }

    BidiFlow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val networkIn = b.add(Flow[ByteString].via(new UpgradeStage))
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

  def simpleTls: BidiFlow[SslTlsInbound, ByteString, ByteString, SendBytes, NotUsed] =
    BidiFlow.fromFlowsMat(
      Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) ⇒ bytes },
      Flow[ByteString].map(SendBytes))(Keep.none)
}
