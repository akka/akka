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
import akka.http.scaladsl.model.{ IllegalResponseException, HttpRequest, HttpResponse, ResponseEntity }
import akka.http.impl.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.impl.engine.parsing._
import akka.http.impl.util._
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.{ InHandler, OutHandler }

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
  def apply(hostHeader: headers.Host,
            settings: ClientConnectionSettings,
            log: LoggingAdapter): Http.ClientLayer = {
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
        val rootParser = new HttpResponseParser(parserSettings, HttpHeaderParser(parserSettings) { info ⇒
          if (parserSettings.illegalHeaderWarnings)
            logParsingError(info withSummaryPrepended "Illegal response header", log, parserSettings.errorLoggingVerbosity)
        })
        new ResponseParsingMerge(rootParser)
      }

      val responsePrep = Flow[List[ParserOutput.ResponseOutput]]
        .mapConcat(ConstantFun.scalaIdentityFunction)
        .via(new PrepareResponse(parserSettings))

      val terminationFanout = b.add(Broadcast[HttpResponse](2))

      val logger = b.add(MapError[ByteString] { case t ⇒ log.error(t, "Outgoing request stream error"); t }.named("errorLogger"))
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

    One2OneBidiFlow[HttpRequest, HttpResponse](-1) atop core
  }

  // a simple merge stage that simply forwards its first input and ignores its second input
  // (the terminationBackchannelInput), but applies a special completion handling
  private object TerminationMerge extends GraphStage[FanInShape2[RequestRenderingContext, HttpResponse, RequestRenderingContext]] {
    private val requests = Inlet[RequestRenderingContext]("requests")
    private val responses = Inlet[HttpResponse]("responses")
    private val out = Outlet[RequestRenderingContext]("out")

    override def initialAttributes = Attributes.name("TerminationMerge")

    val shape = new FanInShape2(requests, responses, out)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      passAlong(requests, out, doFinish = false, doFail = true)
      setHandler(out, eagerTerminateOutput)

      setHandler(responses, new InHandler {
        override def onPush(): Unit = pull(responses)
      })

      override def preStart(): Unit = {
        pull(requests)
        pull(responses)
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

    private val in = Inlet[ResponseOutput]("PrepareResponse.in")
    private val out = Outlet[HttpResponse]("PrepareResponse.out")

    val shape = new FlowShape(in, out)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
      private var entitySource: SubSourceOutlet[ResponseOutput] = _
      private def entitySubstreamStarted = entitySource ne null
      private def idle = this
      private var completionDeferred = false
      private var completeOnMessageEnd = false

      def setIdleHandlers(): Unit =
        if (completeOnMessageEnd || completionDeferred) completeStage()
        else setHandlers(in, out, idle)

      def onPush(): Unit = grab(in) match {
        case ResponseStart(statusCode, protocol, headers, entityCreator, closeRequested) ⇒
          val entity = createEntity(entityCreator) withSizeLimit parserSettings.maxContentLength
          push(out, HttpResponse(statusCode, headers, entity, protocol))
          completeOnMessageEnd = closeRequested

        case MessageStartError(_, info) ⇒
          throw IllegalResponseException(info)

        case other ⇒
          throw new IllegalStateException(s"ResponseStart expected but $other received.")
      }

      def onPull(): Unit = {
        if (!entitySubstreamStarted) pull(in)
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
        def onPush(): Unit = grab(in) match {
          case MessageEnd ⇒
            if (isAvailable(out)) pull(in)
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
        override def onPush(): Unit = grab(in) match {
          case MessageEnd ⇒
            entitySource.complete()
            entitySource = null
            // there was a deferred pull from upstream
            // while we were streaming the entity
            if (isAvailable(out)) pull(in)
            setIdleHandlers()

          case messagePart ⇒
            entitySource.push(messagePart)
        }

        override def onPull(): Unit = pull(in)

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
            pull(in)
            setHandler(in, waitForMessageEnd)
            setHandler(out, waitForMessageEnd)
            entity

          case StreamedEntityCreator(creator) ⇒
            entitySource = new SubSourceOutlet[ResponseOutput]("EntitySource")
            entitySource.setHandler(substreamHandler)
            setHandler(in, substreamHandler)
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
    private val dataInput = Inlet[SessionBytes]("data")
    private val bypassInput = Inlet[BypassData]("request")
    private val out = Outlet[List[ResponseOutput]]("out")

    override def initialAttributes = Attributes.name("ResponseParsingMerge")

    val shape = new FanInShape2(dataInput, bypassInput, out)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      // each connection uses a single (private) response parser instance for all its responses
      // which builds a cache of all header instances seen on that connection
      val parser = rootParser.createShallowCopy()
      var waitingForMethod = true

      setHandler(bypassInput, new InHandler {
        override def onPush(): Unit = {
          val responseContext = grab(bypassInput)
          parser.setContextForNextResponse(responseContext)
          val output = parser.parseBytes(ByteString.empty)
          drainParser(output)
        }
        override def onUpstreamFinish(): Unit =
          if (waitingForMethod) completeStage()
      })

      setHandler(dataInput, new InHandler {
        override def onPush(): Unit = {
          val bytes = grab(dataInput)
          val output = parser.parseSessionBytes(bytes)
          drainParser(output)
        }
        override def onUpstreamFinish(): Unit =
          if (waitingForMethod) completeStage()
          else {
            if (parser.onUpstreamFinish()) {
              completeStage()
            } else {
              emit(out, parser.onPull() :: Nil, () ⇒ completeStage())
            }
          }
      })

      setHandler(out, eagerTerminateOutput)

      val getNextMethod = () ⇒ {
        waitingForMethod = true
        if (isClosed(bypassInput)) completeStage()
        else pull(bypassInput)
      }

      val getNextData = () ⇒ {
        waitingForMethod = false
        if (isClosed(dataInput)) completeStage()
        else pull(dataInput)
      }

      @tailrec def drainParser(current: ResponseOutput, b: ListBuffer[ResponseOutput] = ListBuffer.empty): Unit = {
        def e(output: List[ResponseOutput], andThen: () ⇒ Unit): Unit =
          if (output.nonEmpty) emit(out, output, andThen)
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
}
