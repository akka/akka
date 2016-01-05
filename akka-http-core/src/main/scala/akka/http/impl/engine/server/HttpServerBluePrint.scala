/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress
import java.util.Random
import akka.stream.impl.fusing.GraphInterpreter
import scala.collection.immutable
import org.reactivestreams.{ Publisher, Subscriber }
import scala.util.control.NonFatal
import akka.event.LoggingAdapter
import akka.http.ServerSettings
import akka.http.impl.engine.HttpConnectionTimeoutException
import akka.http.impl.engine.parsing.ParserOutput._
import akka.http.impl.engine.parsing._
import akka.http.impl.engine.rendering.{ HttpResponseRendererFactory, ResponseRenderingContext, ResponseRenderingOutput }
import akka.http.impl.engine.ws._
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.impl.ConstantFun
import akka.stream.io._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import akka.http.scaladsl.model.ws.Message
import akka.stream.impl.fusing.SubSource

/**
 * INTERNAL API
 *
 *
 * HTTP pipeline setup (without the underlying SSL/TLS (un)wrapping and the websocket switch):
 *
 *                 +----------+          +-------------+          +-------------+             +-----------+
 *    HttpRequest  |          |   Http-  |  request-   | Request- |             |   Request-  | request-  | ByteString
 *  | <------------+          <----------+ Preparation <----------+             <-------------+  Parsing  <-----------
 *  |              |          |  Request |             | Output   |             |   Output    |           |
 *  |              |          |          +-------------+          |             |             +-----------+
 *  |              |          |                                   |             |
 *  | Application- | One2One- |                                   | controller- |
 *  | Flow         |   Bidi   |                                   |    Stage    |
 *  |              |          |                                   |             |
 *  |              |          |                                   |             |             +-----------+
 *  | HttpResponse |          |           HttpResponse            |             |  Response-  | renderer- | ByteString
 *  v ------------->          +----------------------------------->             +-------------> Pipeline  +---------->
 *                 |          |                                   |             |  Rendering- |           |
 *                 +----------+                                   +-------------+  Context    +-----------+
 */
private[http] object HttpServerBluePrint {
  def apply(settings: ServerSettings, remoteAddress: Option[InetSocketAddress], log: LoggingAdapter): Http.ServerLayer = {
    val theStack =
      userHandlerGuard(settings.pipeliningLimit) atop
        requestPreparation(settings) atop
        controller(settings, log) atop
        parsingRendering(settings, log) atop
        new ProtocolSwitchStage(settings, log) atop
        unwrapTls

    theStack.withAttributes(HttpAttributes.remoteAddress(remoteAddress))
  }

  val unwrapTls: BidiFlow[ByteString, SslTlsOutbound, SslTlsInbound, ByteString, Unit] =
    BidiFlow.fromFlows(Flow[ByteString].map(SendBytes), Flow[SslTlsInbound].collect { case x: SessionBytes ⇒ x.bytes })

  def parsingRendering(settings: ServerSettings, log: LoggingAdapter): BidiFlow[ResponseRenderingContext, ResponseRenderingOutput, ByteString, RequestOutput, Unit] =
    BidiFlow.fromFlows(rendering(settings, log), parsing(settings, log))

  def controller(settings: ServerSettings, log: LoggingAdapter): BidiFlow[HttpResponse, ResponseRenderingContext, RequestOutput, RequestOutput, Unit] =
    BidiFlow.fromGraph(new ControllerStage(settings, log)).reversed

  def requestPreparation(settings: ServerSettings): BidiFlow[HttpResponse, HttpResponse, RequestOutput, HttpRequest, Unit] =
    BidiFlow.fromFlows(Flow[HttpResponse], new PrepareRequests(settings))

  final class PrepareRequests(settings: ServerSettings) extends GraphStage[FlowShape[RequestOutput, HttpRequest]] {
    val in = Inlet[RequestOutput]("RequestStartThenRunIgnore.in")
    val out = Outlet[HttpRequest]("RequestStartThenRunIgnore.out")
    override val shape: FlowShape[RequestOutput, HttpRequest] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
      val remoteAddress = inheritedAttributes.get[HttpAttributes.RemoteAddress].flatMap(_.address)

      val idle = new InHandler {
        def onPush(): Unit = grab(in) match {
          case RequestStart(method, uri, protocol, hdrs, entityCreator, _, _) ⇒
            val effectiveMethod = if (method == HttpMethods.HEAD && settings.transparentHeadRequests) HttpMethods.GET else method
            val effectiveHeaders =
              if (settings.remoteAddressHeader && remoteAddress.isDefined)
                headers.`Remote-Address`(RemoteAddress(remoteAddress.get)) +: hdrs
              else hdrs

            val entity = createEntity(entityCreator) withSizeLimit settings.parserSettings.maxContentLength
            push(out, HttpRequest(effectiveMethod, uri, effectiveHeaders, entity, protocol))
        }
      }
      setHandler(in, idle)

      def createEntity(creator: EntityCreator[RequestOutput, RequestEntity]): RequestEntity =
        creator match {
          case StrictEntityCreator(entity) ⇒ entity
          case StreamedEntityCreator(creator) ⇒
            val entitySource = new SubSourceOutlet[RequestOutput]("EntitySource")
            entitySource.setHandler(new OutHandler {
              def onPull(): Unit = pull(in)
            })
            setHandler(in, new InHandler {
              def onPush(): Unit = grab(in) match {
                case MessageEnd ⇒
                  entitySource.complete()
                  setHandler(in, idle)
                case x ⇒ entitySource.push(x)
              }
              override def onUpstreamFinish(): Unit = completeStage()
            })
            creator(Source.fromGraph(entitySource.source))
        }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }

  def parsing(settings: ServerSettings, log: LoggingAdapter): Flow[ByteString, RequestOutput, Unit] = {
    import settings._

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpRequestParser(parserSettings, rawRequestUriHeader,
      HttpHeaderParser(parserSettings) { info ⇒
        if (parserSettings.illegalHeaderWarnings)
          logParsingError(info withSummaryPrepended "Illegal request header", log, parserSettings.errorLoggingVerbosity)
      })

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

    Flow[ByteString].transform(() ⇒
      // each connection uses a single (private) request parser instance for all its requests
      // which builds a cache of all header instances seen on that connection
      rootParser.createShallowCopy().stage).named("rootParser")
      .map(establishAbsoluteUri)
  }

  def rendering(settings: ServerSettings, log: LoggingAdapter): Flow[ResponseRenderingContext, ResponseRenderingOutput, Unit] = {
    import settings._

    val responseRendererFactory = new HttpResponseRendererFactory(serverHeader, responseHeaderSizeHint, log)

    val errorHandler: Throwable ⇒ Unit = {
      // idle timeouts should not result in errors in the log. See 19058.
      case timeout: HttpConnectionTimeoutException ⇒ log.debug(s"Closing HttpConnection due to timeout: ${timeout.getMessage}")
      case t                                       ⇒ log.error(t, "Outgoing response stream error")
    }

    Flow[ResponseRenderingContext]
      .via(Flow[ResponseRenderingContext].transform(() ⇒ responseRendererFactory.newRenderer).named("renderer"))
      .flatMapConcat(ConstantFun.scalaIdentityFunction)
      .via(Flow[ResponseRenderingOutput].transform(() ⇒ errorHandling(errorHandler)).named("errorLogger"))
  }

  class ControllerStage(settings: ServerSettings, log: LoggingAdapter)
    extends GraphStage[BidiShape[RequestOutput, RequestOutput, HttpResponse, ResponseRenderingContext]] {
    private val requestParsingIn = Inlet[RequestOutput]("requestParsingIn")
    private val requestPrepOut = Outlet[RequestOutput]("requestPrepOut")
    private val httpResponseIn = Inlet[HttpResponse]("httpResponseIn")
    private val responseCtxOut = Outlet[ResponseRenderingContext]("responseCtxOut")

    override def initialAttributes = Attributes.name("ControllerStage")

    val shape = new BidiShape(requestParsingIn, requestPrepOut, httpResponseIn, responseCtxOut)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      val pullHttpResponseIn = () ⇒ pull(httpResponseIn)
      var openRequests = immutable.Queue[RequestStart]()
      var oneHundredContinueResponsePending = false
      var pullSuppressed = false
      var messageEndPending = false

      setHandler(requestParsingIn, new InHandler {
        def onPush(): Unit =
          grab(requestParsingIn) match {
            case r: RequestStart ⇒
              openRequests = openRequests.enqueue(r)
              messageEndPending = r.createEntity.isInstanceOf[StreamedEntityCreator[_, _]]
              val rs = if (r.expect100Continue) {
                oneHundredContinueResponsePending = true
                r.copy(createEntity = with100ContinueTrigger(r.createEntity))
              } else r
              push(requestPrepOut, rs)
            case MessageEnd ⇒
              messageEndPending = false
              push(requestPrepOut, MessageEnd)
            case MessageStartError(status, info) ⇒ finishWithIllegalRequestError(status, info)
            case x                               ⇒ push(requestPrepOut, x)
          }
        override def onUpstreamFinish() =
          if (openRequests.isEmpty) completeStage()
          else complete(requestPrepOut)
      })

      setHandler(requestPrepOut, new OutHandler {
        def onPull(): Unit =
          if (oneHundredContinueResponsePending) pullSuppressed = true
          else pull(requestParsingIn)
        override def onDownstreamFinish() = cancel(requestParsingIn)
      })

      setHandler(httpResponseIn, new InHandler {
        def onPush(): Unit = {
          val response = grab(httpResponseIn)
          val requestStart = openRequests.head
          openRequests = openRequests.tail
          val isEarlyResponse = messageEndPending && openRequests.isEmpty
          if (isEarlyResponse && response.status.isSuccess)
            log.warning(
              """Sending 2xx response before end of request was received...
                |Note that the connection will be closed after this response. Also, many clients will not read early responses!
                |Consider waiting for the request end before dispatching this response!""".stripMargin)
          val close = requestStart.closeRequested ||
            requestStart.expect100Continue && oneHundredContinueResponsePending ||
            isClosed(requestParsingIn) && openRequests.isEmpty ||
            isEarlyResponse
          emit(responseCtxOut, ResponseRenderingContext(response, requestStart.method, requestStart.protocol, close),
            pullHttpResponseIn)
          if (close) complete(responseCtxOut)
        }
        override def onUpstreamFinish() =
          if (openRequests.isEmpty && isClosed(requestParsingIn)) completeStage()
          else complete(responseCtxOut)
        override def onUpstreamFailure(ex: Throwable): Unit =
          ex match {
            case EntityStreamException(errorInfo) ⇒
              // the application has forwarded a request entity stream error to the response stream
              finishWithIllegalRequestError(StatusCodes.BadRequest, errorInfo)

            case EntityStreamSizeException(limit, contentLength) ⇒
              val summary = contentLength match {
                case Some(cl) ⇒ s"Request Content-Length of $cl bytes exceeds the configured limit of $limit bytes"
                case None     ⇒ s"Aggregated data length of request entity exceeds the configured limit of $limit bytes"
              }
              val info = ErrorInfo(summary, "Consider increasing the value of akka.http.server.parsing.max-content-length")
              finishWithIllegalRequestError(StatusCodes.RequestEntityTooLarge, info)

            case NonFatal(e) ⇒
              log.error(e, "Internal server error, sending 500 response")
              emitErrorResponse(HttpResponse(StatusCodes.InternalServerError))
          }
      })

      class ResponseCtxOutHandler extends OutHandler {
        override def onPull() = {}
        override def onDownstreamFinish() =
          cancel(httpResponseIn) // we cannot fully completeState() here as the websocket pipeline would not complete properly
      }
      setHandler(responseCtxOut, new ResponseCtxOutHandler {
        override def onPull() = {
          pull(httpResponseIn)
          // after the initial pull here we only ever pull after having emitted in `onPush` of `httpResponseIn`
          setHandler(responseCtxOut, new ResponseCtxOutHandler)
        }
      })

      def finishWithIllegalRequestError(status: StatusCode, info: ErrorInfo): Unit = {
        logParsingError(info withSummaryPrepended s"Illegal request, responding with status '$status'",
          log, settings.parserSettings.errorLoggingVerbosity)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        emitErrorResponse(HttpResponse(status, entity = msg))
      }

      def emitErrorResponse(response: HttpResponse): Unit =
        emit(responseCtxOut, ResponseRenderingContext(response, closeRequested = true), () ⇒ complete(responseCtxOut))

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
      val emit100ContinueResponse =
        getAsyncCallback[Unit] { _ ⇒
          oneHundredContinueResponsePending = false
          emit(responseCtxOut, ResponseRenderingContext(HttpResponse(StatusCodes.Continue)))
          if (pullSuppressed) {
            pullSuppressed = false
            pull(requestParsingIn)
          }
        }

      def with100ContinueTrigger[T <: ParserOutput](createEntity: EntityCreator[T, RequestEntity]) =
        StreamedEntityCreator {
          createEntity.compose[Source[T, Unit]] {
            _.via(Flow[T].transform(() ⇒ new PushPullStage[T, T] {
              private var oneHundredContinueSent = false
              def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)
              def onPull(ctx: Context[T]) = {
                if (!oneHundredContinueSent) {
                  oneHundredContinueSent = true
                  emit100ContinueResponse.invoke(())
                }
                ctx.pull()
              }
            }).named("expect100continueTrigger"))
          }
        }
    }
  }

  /**
   * Ensures that the user handler
   *  - produces exactly one response per request
   *  - has not more than `pipeliningLimit` responses outstanding
   */
  def userHandlerGuard(pipeliningLimit: Int): BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Unit] =
    One2OneBidiFlow[HttpRequest, HttpResponse](pipeliningLimit).reversed

  private class ProtocolSwitchStage(settings: ServerSettings, log: LoggingAdapter)
    extends GraphStage[BidiShape[ResponseRenderingOutput, ByteString, ByteString, ByteString]] {

    private val fromNet = Inlet[ByteString]("fromNet")
    private val toNet = Outlet[ByteString]("toNet")

    private val toHttp = Outlet[ByteString]("toHttp")
    private val fromHttp = Inlet[ResponseRenderingOutput]("fromHttp")

    override def initialAttributes = Attributes.name("ProtocolSwitchStage")

    override val shape = BidiShape(fromHttp, toNet, fromNet, toHttp)

    def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      import akka.http.impl.engine.rendering.ResponseRenderingOutput._

      setHandler(fromHttp, new InHandler {
        override def onPush(): Unit =
          grab(fromHttp) match {
            case HttpData(b) ⇒ push(toNet, b)
            case SwitchToWebsocket(bytes, handlerFlow) ⇒
              push(toNet, bytes)
              complete(toHttp)
              cancel(fromHttp)
              switchToWebsocket(handlerFlow)
          }
      })
      setHandler(toNet, new OutHandler {
        override def onPull(): Unit = pull(fromHttp)
      })

      setHandler(fromNet, new InHandler {
        def onPush(): Unit = push(toHttp, grab(fromNet))

        // propagate error but don't close stage yet to prevent fromHttp/fromWs being cancelled
        // too eagerly
        override def onUpstreamFailure(ex: Throwable): Unit = fail(toHttp, ex)
      })
      setHandler(toHttp, new OutHandler {
        override def onPull(): Unit = pull(fromNet)
        override def onDownstreamFinish(): Unit = ()
      })

      private var activeTimers = 0
      private def timeout = ActorMaterializer.downcast(materializer).settings.subscriptionTimeoutSettings.timeout
      private def addTimeout(s: SubscriptionTimeout): Unit = {
        if (activeTimers == 0) setKeepGoing(true)
        activeTimers += 1
        scheduleOnce(s, timeout)
      }
      private def cancelTimeout(s: SubscriptionTimeout): Unit =
        if (isTimerActive(s)) {
          activeTimers -= 1
          if (activeTimers == 0) setKeepGoing(false)
          cancelTimer(s)
        }
      override def onTimer(timerKey: Any): Unit = timerKey match {
        case SubscriptionTimeout(f) ⇒
          activeTimers -= 1
          if (activeTimers == 0) setKeepGoing(false)
          f()
      }

      /*
       * Websocket support
       */
      def switchToWebsocket(handlerFlow: Either[Flow[FrameEvent, FrameEvent, Any], Flow[Message, Message, Any]]): Unit = {
        val frameHandler = handlerFlow match {
          case Left(frameHandler) ⇒ frameHandler
          case Right(messageHandler) ⇒
            Websocket.stack(serverSide = true, maskingRandomFactory = settings.websocketRandomFactory, log = log).join(messageHandler)
        }
        val sinkIn = new SubSinkInlet[ByteString]("FrameSink")
        val sourceOut = new SubSourceOutlet[ByteString]("FrameSource")

        val timeoutKey = SubscriptionTimeout(() ⇒ {
          sourceOut.timeout(timeout)
          if (sourceOut.isClosed) completeStage()
        })
        addTimeout(timeoutKey)

        sinkIn.setHandler(new InHandler {
          override def onPush(): Unit = push(toNet, sinkIn.grab())
        })
        setHandler(toNet, new OutHandler {
          override def onPull(): Unit = sinkIn.pull()
        })

        setHandler(fromNet, new InHandler {
          override def onPush(): Unit = sourceOut.push(grab(fromNet))
        })
        sourceOut.setHandler(new OutHandler {
          override def onPull(): Unit = {
            if (!hasBeenPulled(fromNet)) pull(fromNet)
            cancelTimeout(timeoutKey)
            sourceOut.setHandler(new OutHandler {
              override def onPull(): Unit = pull(fromNet)
            })
          }
        })

        Websocket.framing.join(frameHandler).runWith(sourceOut.source, sinkIn.sink)(subFusingMaterializer)
      }
    }
  }

  private case class SubscriptionTimeout(andThen: () ⇒ Unit)
}
