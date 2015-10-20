/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import language.existentials
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.stream.io.{ SessionBytes, SslTlsInbound, SendBytes, SslTlsOutbound }
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl._
import akka.http.ClientConnectionSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.{ IllegalResponseException, HttpMethod, HttpRequest, HttpResponse }
import akka.http.impl.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.impl.engine.parsing._
import akka.http.impl.util._
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler

/**
 * INTERNAL API
 */
private[http] object OutgoingConnectionBlueprint {
  /*
    Stream Setup
    ============

    requestIn                                            +----------+
    +-----------------------------------------------+--->|  Termi-  |   requestRendering
                                                    |    |  nation  +---------------------> |
                 +-------------------------------------->|  Merge   |                       |
                 | Termination Backchannel          |    +----------+                       |  TCP-
                 |                                  |                                       |  level
                 |                                  | Method                                |  client
                 |                +------------+    | Bypass                                |  flow
    responseOut  |  responsePrep  |  Response  |<---+                                       |
    <------------+----------------|  Parsing   |                                            |
                                  |  Merge     |<------------------------------------------ V
                                  +------------+
  */
  def apply(hostHeader: Host,
            settings: ClientConnectionSettings,
            log: LoggingAdapter): Http.ClientLayer = {
    import settings._

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpResponseParser(parserSettings, HttpHeaderParser(parserSettings) { info ⇒
      if (parserSettings.illegalHeaderWarnings)
        logParsingError(info withSummaryPrepended "Illegal response header", log, parserSettings.errorLoggingVerbosity)
    })

    val requestRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, log)

    val requestRendering: Flow[HttpRequest, ByteString, Unit] = Flow[HttpRequest]
      .map(RequestRenderingContext(_, hostHeader))
      .via(Flow[RequestRenderingContext].map(requestRendererFactory.renderToSource).named("renderer"))
      .flatten(FlattenStrategy.concat)

    val methodBypass = Flow[HttpRequest].map(_.method)

    import ParserOutput._
    val responsePrep = Flow[List[ResponseOutput]]
      .mapConcat(identityFunc)
      .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
      .via(headAndTailFlow)
      .collect {
        case (ResponseStart(statusCode, protocol, headers, createEntity, _), entityParts) ⇒
          HttpResponse(statusCode, headers, createEntity(entityParts), protocol)
        case (MessageStartError(_, info), _) ⇒ throw IllegalResponseException(info)
      }

    val core = BidiFlow() { implicit b ⇒
      import FlowGraph.Implicits._
      val methodBypassFanout = b.add(Broadcast[HttpRequest](2, eagerCancel = true))
      val responseParsingMerge = b.add(new ResponseParsingMerge(rootParser))

      val terminationFanout = b.add(Broadcast[HttpResponse](2))
      val terminationMerge = b.add(TerminationMerge)

      val logger = b.add(Flow[ByteString].transform(() ⇒ errorLogger(log, "Outgoing request stream error")).named("errorLogger"))
      val wrapTls = b.add(Flow[ByteString].map(SendBytes))
      terminationMerge.out ~> requestRendering ~> logger ~> wrapTls

      val unwrapTls = b.add(Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) ⇒ bytes })
      unwrapTls ~> responseParsingMerge.in0

      methodBypassFanout.out(0) ~> terminationMerge.in0

      methodBypassFanout.out(1) ~> methodBypass ~> responseParsingMerge.in1

      responseParsingMerge.out ~> responsePrep ~> terminationFanout.in
      terminationFanout.out(0) ~> terminationMerge.in1

      BidiShape(
        methodBypassFanout.in,
        wrapTls.outlet,
        unwrapTls.inlet,
        terminationFanout.out(1))
    }

    One2OneBidiFlow[HttpRequest, HttpResponse](-1) atop core
  }

  // a simple merge stage that simply forwards its first input and ignores its second input
  // (the terminationBackchannelInput), but applies a special completion handling
  private object TerminationMerge extends GraphStage[FanInShape2[HttpRequest, HttpResponse, HttpRequest]] {
    private val requests = Inlet[HttpRequest]("requests")
    private val responses = Inlet[HttpResponse]("responses")
    private val out = Outlet[HttpRequest]("out")

    val shape = new FanInShape2(requests, responses, out)

    override def createLogic = new GraphStageLogic(shape) {
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
   * A merge that follows this logic:
   * 1. Wait on the methodBypass for the method of the request corresponding to the next response to be received
   * 2. Read from the dataInput until exactly one response has been fully received
   * 3. Go back to 1.
   */
  class ResponseParsingMerge(rootParser: HttpResponseParser) extends GraphStage[FanInShape2[ByteString, HttpMethod, List[ResponseOutput]]] {
    private val dataInput = Inlet[ByteString]("data")
    private val methodBypassInput = Inlet[HttpMethod]("method")
    private val out = Outlet[List[ResponseOutput]]("out")

    val shape = new FanInShape2(dataInput, methodBypassInput, out)

    override def createLogic = new GraphStageLogic(shape) {
      // each connection uses a single (private) response parser instance for all its responses
      // which builds a cache of all header instances seen on that connection
      val parser = rootParser.createShallowCopy()
      var methodBypassCompleted = false
      var waitingForMethod = true

      setHandler(methodBypassInput, new InHandler {
        override def onPush(): Unit = {
          val method = grab(methodBypassInput)
          parser.setRequestMethodForNextResponse(method)
          val output = parser.onPush(ByteString.empty)
          drainParser(output)
        }
        override def onUpstreamFinish(): Unit =
          if (waitingForMethod) completeStage()
          else methodBypassCompleted = true
      })

      setHandler(dataInput, new InHandler {
        override def onPush(): Unit = {
          val bytes = grab(dataInput)
          val output = parser.onPush(bytes)
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

      val getNextMethod = () ⇒
        if (methodBypassCompleted) completeStage()
        else {
          pull(methodBypassInput)
          waitingForMethod = true
        }

      val getNextData = () ⇒ {
        waitingForMethod = false
        pull(dataInput)
      }

      @tailrec def drainParser(current: ResponseOutput, b: ListBuffer[ResponseOutput] = ListBuffer.empty): Unit = {
        def e(output: List[ResponseOutput], andThen: () ⇒ Unit): Unit =
          if (output.nonEmpty) emit(out, output, andThen)
          else andThen()
        current match {
          case NeedNextRequestMethod ⇒
            e(b.result(), getNextMethod)
          case StreamEnd ⇒
            e(b.result(), () ⇒ completeStage())
          case NeedMoreData ⇒
            e(b.result(), getNextData)
          case x ⇒ drainParser(parser.onPull(), b += x)
        }
      }

      override def preStart(): Unit = getNextMethod()
    }
  }
}
