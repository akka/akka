/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress
import java.util.Random

import akka.actor.{ ActorRef, Deploy, Props }
import akka.event.LoggingAdapter
import akka.http.ServerSettings
import akka.http.impl.engine.TokenSourceActor
import akka.http.impl.engine.parsing.ParserOutput._
import akka.http.impl.engine.parsing._
import akka.http.impl.engine.rendering.{ HttpResponseRendererFactory, ResponseRenderingContext, ResponseRenderingOutput }
import akka.http.impl.engine.ws.Websocket.SwitchToWebsocketToken
import akka.http.impl.engine.ws._
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.io._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import org.reactivestreams.{ Publisher, Subscriber }

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[http] object HttpServerBluePrint {
  def apply(settings: ServerSettings, remoteAddress: Option[InetSocketAddress], log: LoggingAdapter)(implicit mat: Materializer): Http.ServerLayer = {
    import settings._

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpRequestParser(parserSettings, rawRequestUriHeader,
      HttpHeaderParser(parserSettings) { info ⇒
        if (parserSettings.illegalHeaderWarnings)
          logParsingError(info withSummaryPrepended "Illegal request header", log, parserSettings.errorLoggingVerbosity)
      })

    val ws = websocketSetup
    val responseRendererFactory = new HttpResponseRendererFactory(serverHeader, responseHeaderSizeHint, log)

    @volatile var oneHundredContinueRef: Option[ActorRef] = None // FIXME: unnecessary after fixing #16168
    val oneHundredContinueSource = StreamUtils.oneTimeSource(Source.actorPublisher[OneHundredContinue.type] {
      Props {
        val actor = new TokenSourceActor(OneHundredContinue)
        oneHundredContinueRef = Some(actor.context.self)
        actor
      }.withDeploy(Deploy.local)
    }, errorMsg = "Http.serverLayer is currently not reusable. You need to create a new instance for each materialization.")

    def establishAbsoluteUri(requestOutput: RequestOutput): RequestOutput = requestOutput match {
      case start: RequestStart ⇒
        try {
          val effectiveUri = HttpRequest.effectiveUri(start.uri, start.headers, securedConnection = false, defaultHostHeader)
          start.copy(uri = effectiveUri)
        } catch {
          case e: IllegalUriException ⇒
            MessageStartError(StatusCodes.BadRequest, ErrorInfo("Request is missing required `Host` header", e.getMessage))
        }
      case x ⇒ x
    }

    val requestParsingFlow = Flow[ByteString].transform(() ⇒
      // each connection uses a single (private) request parser instance for all its requests
      // which builds a cache of all header instances seen on that connection
      rootParser.createShallowCopy(() ⇒ oneHundredContinueRef).stage).named("rootParser")
      .map(establishAbsoluteUri)

    val requestPreparation =
      Flow[RequestOutput]
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
        .via(headAndTailFlow)
        .map {
          case (RequestStart(method, uri, protocol, hdrs, createEntity, _, _), entityParts) ⇒
            val effectiveMethod = if (method == HttpMethods.HEAD && transparentHeadRequests) HttpMethods.GET else method
            val effectiveHeaders =
              if (settings.remoteAddressHeader && remoteAddress.isDefined)
                headers.`Remote-Address`(RemoteAddress(remoteAddress.get)) +: hdrs
              else hdrs

            HttpRequest(effectiveMethod, uri, effectiveHeaders, createEntity(entityParts), protocol)
          case (_, src) ⇒ src.runWith(Sink.ignore)
        }.collect {
          case r: HttpRequest ⇒ r
        }
        // FIXME #16583 / #18170
        // `buffer` is needed because of current behavior of collect which will queue completion
        // behind an ignored (= not collected) element if there is no demand.
        // `buffer` will ensure demand and therefore make sure that completion is reported eagerly.
        .buffer(1, OverflowStrategy.backpressure)

    // we need to make sure that only one element per incoming request is queueing up in front of
    // the bypassMerge.bypassInput. Otherwise the rising backpressure against the bypassFanout
    // would eventually prevent us from reading the remaining request chunks from the transportIn
    val bypass = Flow[RequestOutput].collect {
      case r: RequestStart      ⇒ r
      case m: MessageStartError ⇒ m
    }

    val rendererPipeline =
      Flow[ResponseRenderingContext]
        .via(Flow[ResponseRenderingContext].transform(() ⇒ new ErrorsTo500ResponseRecovery(log)).named("recover")) // FIXME: simplify after #16394 is closed
        .via(Flow[ResponseRenderingContext].transform(() ⇒ responseRendererFactory.newRenderer).named("renderer"))
        .flatten(FlattenStrategy.concat)
        .via(Flow[ResponseRenderingOutput].transform(() ⇒ errorLogger(log, "Outgoing response stream error")).named("errorLogger"))

    BidiFlow.fromGraph(FlowGraph.create(requestParsingFlow, rendererPipeline, oneHundredContinueSource)((_, _, _) ⇒ ()) { implicit b ⇒
      (requestParsing, renderer, oneHundreds) ⇒
        import FlowGraph.Implicits._

        val bypassFanout = b.add(Broadcast[RequestOutput](2).named("bypassFanout"))
        val bypassMerge = b.add(new BypassMerge(settings, log))
        val bypassInput = bypassMerge.in0
        val bypassOneHundredContinueInput = bypassMerge.in1
        val bypassApplicationInput = bypassMerge.in2

        // HTTP pipeline
        requestParsing.outlet ~> bypassFanout.in
        bypassMerge.out ~> renderer.inlet
        val requestsIn = (bypassFanout.out(0) ~> requestPreparation).outlet

        bypassFanout.out(1) ~> bypass ~> bypassInput
        oneHundreds ~> bypassOneHundredContinueInput

        val switchTokenBroadcast = b.add(Broadcast[ResponseRenderingOutput](2))
        renderer.outlet ~> switchTokenBroadcast
        val switchSource: Outlet[SwitchToWebsocketToken.type] =
          (switchTokenBroadcast ~>
            Flow[ResponseRenderingOutput]
            .collect {
              case _: ResponseRenderingOutput.SwitchToWebsocket ⇒ SwitchToWebsocketToken
            }).outlet

        val http = FlowShape(requestParsing.inlet, switchTokenBroadcast.outlet)

        // Websocket pipeline
        val websocket = b.add(ws.websocketFlow)

        // protocol routing
        val protocolRouter = b.add(WebsocketSwitchRouter)
        val protocolMerge = b.add(new WebsocketMerge(ws.installHandler, settings.websocketRandomFactory, log))

        protocolRouter.out0 ~> http ~> protocolMerge.in0
        protocolRouter.out1 ~> websocket ~> protocolMerge.in1

        // protocol switching
        val wsSwitchTokenMerge = b.add(WsSwitchTokenMerge)
        // feed back switch signal to the protocol router
        switchSource ~> wsSwitchTokenMerge.in1
        wsSwitchTokenMerge.out ~> protocolRouter.in

        val unwrapTls = b.add(Flow[SslTlsInbound] collect { case x: SessionBytes ⇒ x.bytes })
        val wrapTls = b.add(Flow[ByteString].map[SslTlsOutbound](SendBytes))

        unwrapTls ~> wsSwitchTokenMerge.in0
        protocolMerge.out ~> wrapTls

        BidiShape[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest](
          bypassApplicationInput,
          wrapTls.outlet,
          unwrapTls.inlet,
          requestsIn)
    })
  }

  class BypassMerge(settings: ServerSettings, log: LoggingAdapter)
    extends GraphStage[FanInShape3[MessageStart with RequestOutput, OneHundredContinue.type, HttpResponse, ResponseRenderingContext]] {
    private val bypassInput = Inlet[MessageStart with RequestOutput]("bypassInput")
    private val oneHundredContinue = Inlet[OneHundredContinue.type]("100continue")
    private val applicationInput = Inlet[HttpResponse]("applicationInput")
    private val out = Outlet[ResponseRenderingContext]("bypassOut")

    override val shape = new FanInShape3(bypassInput, oneHundredContinue, applicationInput, out)

    override def createLogic = new GraphStageLogic(shape) {
      var requestStart: RequestStart = _

      setHandler(bypassInput, new InHandler {
        override def onPush(): Unit = {
          grab(bypassInput) match {
            case r: RequestStart ⇒
              requestStart = r
              pull(applicationInput)
              if (r.expect100ContinueResponsePending)
                read(oneHundredContinue) { cont ⇒
                  emit(out, ResponseRenderingContext(HttpResponse(StatusCodes.Continue)))
                  requestStart = requestStart.copy(expect100ContinueResponsePending = false)
                }
            case MessageStartError(status, info) ⇒ finishWithError(status, info)
          }
        }
        override def onUpstreamFinish(): Unit =
          requestStart match {
            case null ⇒ completeStage()
            case r    ⇒ requestStart = r.copy(closeRequested = true)
          }
      })

      setHandler(applicationInput, new InHandler {
        override def onPush(): Unit = {
          val response = grab(applicationInput)
          // see the comment on [[OneHundredContinue]] for an explanation of the closing logic here (and more)
          val close = requestStart.closeRequested || requestStart.expect100ContinueResponsePending
          abortReading(oneHundredContinue)
          emit(out, ResponseRenderingContext(response, requestStart.method, requestStart.protocol, close),
            if (close) () ⇒ completeStage() else pullBypass)
        }
        override def onUpstreamFailure(ex: Throwable): Unit =
          ex match {
            case EntityStreamException(errorInfo) ⇒
              // the application has forwarded a request entity stream error to the response stream
              finishWithError(StatusCodes.BadRequest, errorInfo)
            case _ ⇒ failStage(ex)
          }
      })

      def finishWithError(status: StatusCode, info: ErrorInfo): Unit = {
        logParsingError(info withSummaryPrepended s"Illegal request, responding with status '$status'",
          log, settings.parserSettings.errorLoggingVerbosity)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        emit(out, ResponseRenderingContext(HttpResponse(status, entity = msg), closeRequested = true), () ⇒ completeStage())
      }

      setHandler(oneHundredContinue, ignoreTerminateInput) // RK: not sure if this is always correct
      setHandler(out, eagerTerminateOutput)

      val pullBypass = () ⇒
        if (isClosed(bypassInput)) completeStage()
        else {
          pull(bypassInput)
          requestStart = null
        }

      override def preStart(): Unit = {
        pull(bypassInput)
      }
    }
  }

  /**
   * The `Expect: 100-continue` header has a special status in HTTP.
   * It allows the client to send an `Expect: 100-continue` header with the request and then pause request sending
   * (i.e. hold back sending the request entity). The server reads the request headers, determines whether it wants to
   * accept the request and responds with
   *
   * - `417 Expectation Failed`, if it doesn't support the `100-continue` expectation
   * (or if the `Expect` header contains other, unsupported expectations).
   * - a `100 Continue` response,
   * if it is ready to accept the request entity and the client should go ahead with sending it
   * - a final response (like a 4xx to signal some client-side error
   * (e.g. if the request entity length is beyond the configured limit) or a 3xx redirect)
   *
   * Only if the client receives a `100 Continue` response from the server is it allowed to continue sending the request
   * entity. In this case it will receive another response after having completed request sending.
   * So this special feature breaks the normal "one request - one response" logic of HTTP!
   * It therefore requires special handling in all HTTP stacks (client- and server-side).
   *
   * For us this means:
   *
   * - on the server-side:
   * After having read a `Expect: 100-continue` header with the request we package up an `HttpRequest` instance and send
   * it through to the application. Only when (and if) the application then requests data from the entity stream do we
   * send out a `100 Continue` response and continue reading the request entity.
   * The application can therefore determine itself whether it wants the client to send the request entity
   * by deciding whether to look at the request entity data stream or not.
   * If the application sends a response *without* having looked at the request entity the client receives this
   * response *instead of* the `100 Continue` response and the server closes the connection afterwards.
   *
   * - on the client-side:
   * If the user adds a `Expect: 100-continue` header to the request we need to hold back sending the entity until
   * we've received a `100 Continue` response.
   */
  case object OneHundredContinue

  final class ErrorsTo500ResponseRecovery(log: LoggingAdapter)
    extends PushPullStage[ResponseRenderingContext, ResponseRenderingContext] {

    import akka.stream.stage.Context

    private[this] var errorResponse: ResponseRenderingContext = _

    override def onPush(elem: ResponseRenderingContext, ctx: Context[ResponseRenderingContext]) = ctx.push(elem)

    override def onPull(ctx: Context[ResponseRenderingContext]) =
      if (ctx.isFinishing) ctx.pushAndFinish(errorResponse)
      else ctx.pull()

    override def onUpstreamFailure(error: Throwable, ctx: Context[ResponseRenderingContext]) =
      error match {
        case NonFatal(e) ⇒
          log.error(e, "Internal server error, sending 500 response")
          errorResponse = ResponseRenderingContext(HttpResponse(StatusCodes.InternalServerError),
            closeRequested = true)
          ctx.absorbTermination()
        case _ ⇒ ctx.fail(error)
      }
  }

  private trait WebsocketSetup {
    def websocketFlow: Flow[ByteString, ByteString, Any]
    def installHandler(handlerFlow: Flow[FrameEvent, FrameEvent, Any])(implicit mat: Materializer): Unit
  }
  private def websocketSetup: WebsocketSetup = {
    val sinkCell = new StreamUtils.OneTimeWriteCell[Publisher[FrameEvent]]
    val sourceCell = new StreamUtils.OneTimeWriteCell[Subscriber[FrameEvent]]

    val sink = StreamUtils.oneTimePublisherSink[FrameEvent](sinkCell, "frameHandler.in")
    val source = StreamUtils.oneTimeSubscriberSource[FrameEvent](sourceCell, "frameHandler.out")

    val flow = Websocket.framing.join(Flow.fromSinkAndSourceMat(sink, source)(Keep.none))

    new WebsocketSetup {
      def websocketFlow: Flow[ByteString, ByteString, Any] = flow

      def installHandler(handlerFlow: Flow[FrameEvent, FrameEvent, Any])(implicit mat: Materializer): Unit =
        Source(sinkCell.value)
          .via(handlerFlow)
          .to(Sink(sourceCell.value))
          .run()
    }
  }

  private object WebsocketSwitchRouter extends GraphStage[FanOutShape2[AnyRef, ByteString, ByteString]] {
    private val in = Inlet[AnyRef]("in")
    private val httpOut = Outlet[ByteString]("httpOut")
    private val wsOut = Outlet[ByteString]("wsOut")

    override val shape = new FanOutShape2(in, httpOut, wsOut)

    override def createLogic = new GraphStageLogic(shape) {
      var target = httpOut

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          grab(in) match {
            case b: ByteString          ⇒ emit(target, b, pullIn)
            case SwitchToWebsocketToken ⇒ target = wsOut; pullIn()
          }
        }
      })

      setHandler(httpOut, conditionalTerminateOutput(() ⇒ target == httpOut))
      setHandler(wsOut, conditionalTerminateOutput(() ⇒ target == wsOut))

      val pullIn = () ⇒ pull(in)

      override def preStart(): Unit = pullIn()
    }
  }

  private class WebsocketMerge(installHandler: Flow[FrameEvent, FrameEvent, Any] ⇒ Unit, websocketRandomFactory: () ⇒ Random, log: LoggingAdapter) extends GraphStage[FanInShape2[ResponseRenderingOutput, ByteString, ByteString]] {
    private val httpIn = Inlet[ResponseRenderingOutput]("httpIn")
    private val wsIn = Inlet[ByteString]("wsIn")
    private val out = Outlet[ByteString]("out")

    override val shape = new FanInShape2(httpIn, wsIn, out)

    override def createLogic = new GraphStageLogic(shape) {
      var websocketHandlerWasInstalled = false

      setHandler(httpIn, conditionalTerminateInput(() ⇒ !websocketHandlerWasInstalled))
      setHandler(wsIn, conditionalTerminateInput(() ⇒ websocketHandlerWasInstalled))

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          if (websocketHandlerWasInstalled) read(wsIn)(transferBytes)
          else read(httpIn)(transferHttpData)
      })

      val transferBytes = (b: ByteString) ⇒ push(out, b)
      val transferHttpData = (r: ResponseRenderingOutput) ⇒ {
        import ResponseRenderingOutput._
        r match {
          case HttpData(bytes) ⇒ push(out, bytes)
          case SwitchToWebsocket(bytes, handlerFlow) ⇒
            push(out, bytes)
            val frameHandler = handlerFlow match {
              case Left(frameHandler) ⇒ frameHandler
              case Right(messageHandler) ⇒
                Websocket.stack(serverSide = true, maskingRandomFactory = websocketRandomFactory, log = log).join(messageHandler)
            }
            installHandler(frameHandler)
            websocketHandlerWasInstalled = true
        }
      }

      override def postStop(): Unit = {
        // Install a dummy handler to make sure no processors leak because they have
        // never been subscribed to, see #17494 and #17551.
        if (!websocketHandlerWasInstalled) installHandler(Flow[FrameEvent])
      }
    }
  }

  /** A merge for two streams that just forwards all elements and closes the connection when the first input closes. */
  private object WsSwitchTokenMerge extends GraphStage[FanInShape2[ByteString, Websocket.SwitchToWebsocketToken.type, AnyRef]] {
    private val bytes = Inlet[ByteString]("bytes")
    private val token = Inlet[Websocket.SwitchToWebsocketToken.type]("token")
    private val out = Outlet[AnyRef]("out")

    override val shape = new FanInShape2(bytes, token, out)

    override def createLogic = new GraphStageLogic(shape) {
      passAlong(bytes, out, doFinish = true, doFail = true)
      passAlong(token, out, doFinish = false, doFail = true)
      setHandler(out, eagerTerminateOutput)
      override def preStart(): Unit = {
        pull(bytes)
        pull(token)
      }
    }
  }
}
