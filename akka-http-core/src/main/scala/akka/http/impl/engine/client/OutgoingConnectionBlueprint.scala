/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.NotUsed
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ParserSettings }
import akka.stream.impl.ConstantFun

import language.existentials
import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.collection.mutable.ListBuffer
import akka.stream.TLSProtocol._
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, IllegalResponseException, ResponseEntity }
import akka.http.impl.engine.rendering.{ HttpRequestRendererFactory, RequestRenderingContext }
import akka.http.impl.engine.parsing._
import akka.http.impl.util._
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.{ InHandler, OutHandler }
import akka.http.impl.util.LogByteStringTools._

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 */
private[http] object OutgoingConnectionBlueprint {

  type BypassData = HttpResponseParser.ResponseContext

  /*
    Stream Setup
    ============

    requestIn                                            +----------+
    +-----------------------------------------------+--->|  Termi-  |   requestRendering
                                                    |    |  nation  +---------------------> |
                 +-------------------------------------->|  Merge   |                       |
                 | Termination Backchannel          |    +----------+                       |  TCP-
                 |                                  |                                       |  level
                 |                                  | BypassData                            |  client
                 |                +------------+    |                                       |  flow
    responseOut  |  responsePrep  |  Response  |<---+                                       |
    <------------+----------------|  Parsing   |                                            |
                                  |  Merge     |<------------------------------------------ V
                                  +------------+
  */
  def apply(
    hostHeader: headers.Host,
    settings:   ClientConnectionSettings,
    log:        LoggingAdapter): Http.ClientLayer = {
    import settings._

    val core = BidiFlow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val renderingContextCreation = b.add {
        Flow[HttpRequest] map { request ⇒
          val sendEntityTrigger =
            request.headers collectFirst { case headers.Expect.`100-continue` ⇒ Promise[NotUsed]().future }
          RequestRenderingContext(request, hostHeader, sendEntityTrigger)
        }
      }

      val bypassFanout = b.add(Broadcast[RequestRenderingContext](2, eagerCancel = true))

      val terminationMerge = b.add(TerminationMerge)

      val requestRendering: Flow[RequestRenderingContext, ByteString, NotUsed] = {
        val requestRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, log)
        Flow[RequestRenderingContext].flatMapConcat(requestRendererFactory.renderToSource).named("renderer")
      }

      val bypass = Flow[RequestRenderingContext] map { ctx ⇒
        HttpResponseParser.ResponseContext(ctx.request.method, ctx.sendEntityTrigger.map(_.asInstanceOf[Promise[Unit]]))
      }

      val responseParsingMerge = b.add {
        // the initial header parser we initially use for every connection,
        // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
        val rootParser = new HttpResponseParser(parserSettings, HttpHeaderParser(parserSettings, log) { info ⇒
          if (parserSettings.illegalHeaderWarnings)
            logParsingError(info withSummaryPrepended "Illegal response header", log, parserSettings.errorLoggingVerbosity)
        })
        new ResponseParsingMerge(rootParser)
      }

      val responsePrep = Flow[List[ParserOutput.ResponseOutput]]
        .mapConcat(ConstantFun.scalaIdentityFunction)
        .via(new PrepareResponse(parserSettings))

      val terminationFanout = b.add(Broadcast[HttpResponse](2))

      val logger = b.add(Flow[ByteString].mapError { case t ⇒ log.error(t, "Outgoing request stream error"); t }.named("errorLogger"))
      val wrapTls = b.add(Flow[ByteString].map(SendBytes))

      val collectSessionBytes = b.add(Flow[SslTlsInbound].collect { case s: SessionBytes ⇒ s })

      renderingContextCreation.out ~> bypassFanout.in
      bypassFanout.out(0) ~> terminationMerge.in0
      terminationMerge.out ~> requestRendering ~> logger ~> wrapTls

      bypassFanout.out(1) ~> bypass ~> responseParsingMerge.in1
      collectSessionBytes ~> responseParsingMerge.in0

      responseParsingMerge.out ~> responsePrep ~> terminationFanout.in
      terminationFanout.out(0) ~> terminationMerge.in1

      BidiShape(
        renderingContextCreation.in,
        wrapTls.out,
        collectSessionBytes.in,
        terminationFanout.out(1))
    })

    One2OneBidiFlow[HttpRequest, HttpResponse](
      -1,
      outputTruncationException = new UnexpectedConnectionClosureException(_)
    ) atop
      core atop
      logTLSBidiBySetting("client-plain-text", settings.logUnencryptedNetworkBytes)
  }

  // a simple merge stage that simply forwards its first input and ignores its second input
  // (the terminationBackchannelInput), but applies a special completion handling
  private object TerminationMerge extends GraphStage[FanInShape2[RequestRenderingContext, HttpResponse, RequestRenderingContext]] {
    private val requestIn = Inlet[RequestRenderingContext]("TerminationMerge.requestIn")
    private val responseOut = Inlet[HttpResponse]("TerminationMerge.responseOut")
    private val requestContextOut = Outlet[RequestRenderingContext]("TerminationMerge.requestContextOut")

    override def initialAttributes = Attributes.name("TerminationMerge")

    val shape = new FanInShape2(requestIn, responseOut, requestContextOut)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      passAlong(requestIn, requestContextOut, doFinish = false, doFail = true)
      setHandler(requestContextOut, eagerTerminateOutput)

      setHandler(responseOut, new InHandler {
        override def onPush(): Unit = pull(responseOut)
      })

      override def preStart(): Unit = {
        pull(requestIn)
        pull(responseOut)
      }
    }
  }

  import ParserOutput._

  /**
   * This is essentially a three state state machine, it is either 'idle' - waiting for a response to come in
   * or has seen the start of a response and is waiting for either chunks followed by MessageEnd if chunked
   * or just MessageEnd in the case of a strict response.
   *
   * For chunked responses a new substream into the response entity is opened and data is streamed there instead
   * of downstream until end of chunks has been reached.
   */
  private[client] final class PrepareResponse(parserSettings: ParserSettings)
    extends GraphStage[FlowShape[ResponseOutput, HttpResponse]] {

    private val responseOutputIn = Inlet[ResponseOutput]("PrepareResponse.responseOutputIn")
    private val httpResponseOut = Outlet[HttpResponse]("PrepareResponse.httpResponseOut")

    val shape = new FlowShape(responseOutputIn, httpResponseOut)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
      private var entitySource: SubSourceOutlet[ResponseOutput] = _
      private def entitySubstreamStarted = entitySource ne null
      private def idle = this
      private var completionDeferred = false
      private var completeOnMessageEnd = false

      def setIdleHandlers(): Unit =
        if (completeOnMessageEnd || completionDeferred) completeStage()
        else setHandlers(responseOutputIn, httpResponseOut, idle)

      def onPush(): Unit = grab(responseOutputIn) match {
        case ResponseStart(statusCode, protocol, headers, entityCreator, closeRequested) ⇒
          val entity = createEntity(entityCreator) withSizeLimit parserSettings.maxContentLength
          push(httpResponseOut, HttpResponse(statusCode, headers, entity, protocol))
          completeOnMessageEnd = closeRequested

        case MessageStartError(_, info) ⇒
          throw IllegalResponseException(info)

        case other ⇒
          throw new IllegalStateException(s"ResponseStart expected but $other received.")
      }

      def onPull(): Unit = {
        if (!entitySubstreamStarted) pull(responseOutputIn)
      }

      override def onDownstreamFinish(): Unit = {
        // if downstream cancels while streaming entity,
        // make sure we also cancel the entity source, but
        // after being done with streaming the entity
        if (entitySubstreamStarted) {
          completionDeferred = true
        } else {
          completeStage()
        }
      }

      setIdleHandlers()

      // with a strict message there still is a MessageEnd to wait for
      lazy val waitForMessageEnd = new InHandler with OutHandler {
        def onPush(): Unit = grab(responseOutputIn) match {
          case MessageEnd ⇒
            if (isAvailable(httpResponseOut)) pull(responseOutputIn)
            setIdleHandlers()
          case other ⇒ throw new IllegalStateException(s"MessageEnd expected but $other received.")
        }

        override def onPull(): Unit = {
          // ignore pull as we will anyways pull when we get MessageEnd
        }
      }

      // with a streamed entity we push the chunks into the substream
      // until we reach MessageEnd
      private lazy val substreamHandler = new InHandler with OutHandler {
        override def onPush(): Unit = grab(responseOutputIn) match {
          case MessageEnd ⇒
            entitySource.complete()
            entitySource = null
            // there was a deferred pull from upstream
            // while we were streaming the entity
            if (isAvailable(httpResponseOut)) pull(responseOutputIn)
            setIdleHandlers()

          case messagePart ⇒
            entitySource.push(messagePart)
        }

        override def onPull(): Unit = pull(responseOutputIn)

        override def onUpstreamFinish(): Unit = {
          entitySource.complete()
          completeStage()
        }

        override def onUpstreamFailure(reason: Throwable): Unit = {
          entitySource.fail(reason)
          failStage(reason)
        }
      }

      private def createEntity(creator: EntityCreator[ResponseOutput, ResponseEntity]): ResponseEntity = {
        creator match {
          case StrictEntityCreator(entity) ⇒
            // upstream demanded one element, which it just got
            // but we want MessageEnd as well
            pull(responseOutputIn)
            setHandler(responseOutputIn, waitForMessageEnd)
            setHandler(httpResponseOut, waitForMessageEnd)
            entity

          case StreamedEntityCreator(creator) ⇒
            entitySource = new SubSourceOutlet[ResponseOutput]("EntitySource")
            entitySource.setHandler(substreamHandler)
            setHandler(responseOutputIn, substreamHandler)
            creator(Source.fromGraph(entitySource.source))
        }
      }
    }
  }

  /**
   * A merge that follows this logic:
   * 1. Wait on the methodBypass for the method of the request corresponding to the next response to be received
   * 2. Read from the dataInput until exactly one response has been fully received
   * 3. Go back to 1.
   */
  private class ResponseParsingMerge(rootParser: HttpResponseParser)
    extends GraphStage[FanInShape2[SessionBytes, BypassData, List[ResponseOutput]]] {
    private val dataIn = Inlet[SessionBytes]("ResponseParsingMerge.dataIn")
    private val bypassIn = Inlet[BypassData]("ResponseParsingMerge.bypassIn")
    private val responseOut = Outlet[List[ResponseOutput]]("ResponseParsingMerge.responseOut")

    override def initialAttributes = Attributes.name("ResponseParsingMerge")

    val shape = new FanInShape2(dataIn, bypassIn, responseOut)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      // each connection uses a single (private) response parser instance for all its responses
      // which builds a cache of all header instances seen on that connection
      val parser = rootParser.createShallowCopy()
      var waitingForMethod = true

      setHandler(bypassIn, new InHandler {
        override def onPush(): Unit = {
          val responseContext = grab(bypassIn)
          parser.setContextForNextResponse(responseContext)
          val output = parser.parseBytes(ByteString.empty)
          drainParser(output)
        }
        override def onUpstreamFinish(): Unit =
          if (waitingForMethod) completeStage()
      })

      setHandler(dataIn, new InHandler {
        override def onPush(): Unit = {
          val bytes = grab(dataIn)
          val output = parser.parseSessionBytes(bytes)
          drainParser(output)
        }
        override def onUpstreamFinish(): Unit =
          if (waitingForMethod) completeStage()
          else {
            if (parser.onUpstreamFinish()) {
              completeStage()
            } else {
              emit(responseOut, parser.onPull() :: Nil, () ⇒ completeStage())
            }
          }
      })

      setHandler(responseOut, eagerTerminateOutput)

      val getNextMethod = () ⇒ {
        waitingForMethod = true
        if (isClosed(bypassIn)) completeStage()
        else pull(bypassIn)
      }

      val getNextData = () ⇒ {
        waitingForMethod = false
        if (isClosed(dataIn)) completeStage()
        else pull(dataIn)
      }

      @tailrec def drainParser(current: ResponseOutput, b: ListBuffer[ResponseOutput] = ListBuffer.empty): Unit = {
        def e(output: List[ResponseOutput], andThen: () ⇒ Unit): Unit =
          if (output.nonEmpty) emit(responseOut, output, andThen)
          else andThen()
        current match {
          case NeedNextRequestMethod ⇒ e(b.result(), getNextMethod)
          case StreamEnd             ⇒ e(b.result(), () ⇒ completeStage())
          case NeedMoreData          ⇒ e(b.result(), getNextData)
          case x                     ⇒ drainParser(parser.onPull(), b += x)
        }
      }

      override def preStart(): Unit = getNextMethod()
    }
  }

  class UnexpectedConnectionClosureException(outstandingResponses: Int)
    extends RuntimeException(s"The http server closed the connection unexpectedly before delivering responses for $outstandingResponses outstanding requests")
}
