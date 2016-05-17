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
import akka.http.scaladsl.model.{ HttpResponse, HttpMethods }
import akka.http.scaladsl.model.headers.Host

import akka.http.impl.engine.parsing.HttpMessageParser.StateResult
import akka.http.impl.engine.parsing.ParserOutput.{ RemainingBytes, ResponseStart, NeedMoreData }
import akka.http.impl.engine.parsing.{ ParserOutput, HttpHeaderParser, HttpResponseParser }
import akka.http.impl.engine.rendering.{ HttpRequestRendererFactory, RequestRenderingContext }
import akka.http.impl.engine.ws.Handshake.Client.NegotiatedWebSocketSettings
import akka.http.impl.util.StreamUtils

object WebSocketClientBlueprint {
  /**
   * Returns a WebSocketClientLayer that can be materialized once.
   */
  def apply(request: WebSocketRequest,
            settings: ClientConnectionSettings,
            log: LoggingAdapter): Http.WebSocketClientLayer =
    (simpleTls.atopMat(handshake(request, settings, log))(Keep.right) atop
      WebSocket.framing atop
      WebSocket.stack(serverSide = false, maskingRandomFactory = settings.websocketRandomFactory, log = log)).reversed

  /**
   * A bidi flow that injects and inspects the WS handshake and then goes out of the way. This BidiFlow
   * can only be materialized once.
   */
  def handshake(request: WebSocketRequest,
                settings: ClientConnectionSettings,
                log: LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, Future[WebSocketUpgradeResponse]] = {
    import request._
    val result = Promise[WebSocketUpgradeResponse]()

    val valve = StreamUtils.OneTimeValve()

    val (initialRequest, key) = Handshake.Client.buildRequest(uri, extraHeaders, subprotocol.toList, settings.websocketRandomFactory())
    val hostHeader = Host(uri.authority)
    val renderedInitialRequest =
      HttpRequestRendererFactory.renderStrict(RequestRenderingContext(initialRequest, hostHeader), settings, log)

    //from here
    class UpgradeStage extends GraphStage[FlowShape[ByteString, ByteString]] {

      val out: Outlet[ByteString] = Outlet("UpgradeStage.out")
      val in: Inlet[ByteString] = Inlet("UpgradeStage.in")

      override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) with InHandler with OutHandler {

          def initial: InHandler = parsingResponse

          def parsingResponse: InHandler = new InHandler {
            // a special version of the parser which only parses one message and then reports the remaining data
            // if some is available
            val parser = new HttpResponseParser(settings.parserSettings, HttpHeaderParser(settings.parserSettings)()) {
              var first = true
              override def handleInformationalResponses = false
              override protected def parseMessage(input: ByteString, offset: Int): Unit = {
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

            def onPush(): Unit = {
              val elem = grab(in)
              parser.parseBytes(elem) match {
                case NeedMoreData ⇒ pull(in)
                case ResponseStart(status, protocol, headers, entity, close) ⇒
                  val response = HttpResponse(status, headers, protocol = protocol)
                  Handshake.Client.validateResponse(response, subprotocol.toList, key) match {
                    case Right(NegotiatedWebSocketSettings(protocol)) ⇒
                      result.success(ValidUpgrade(response, protocol))

                      become(transparent)
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
          }

          def transparent: InHandler = new InHandler {
            val elem = grab(in)
            def onPush(): Unit = push(out, elem)
          }
          setHandlers(in, out, this)
        }
    }

    //to here

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
