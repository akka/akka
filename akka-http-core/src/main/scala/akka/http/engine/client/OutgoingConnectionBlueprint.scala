/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import language.existentials
import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer
import akka.stream.stage._
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.OperationAttributes._
import akka.http.model.{ IllegalResponseException, HttpMethod, HttpRequest, HttpResponse }
import akka.http.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.engine.parsing._
import akka.http.util._

/**
 * INTERNAL API
 */
private[http] object OutgoingConnectionBlueprint {

  type ClientShape = BidiShape[HttpRequest, ByteString, ByteString, HttpResponse]

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
  def apply(remoteAddress: InetSocketAddress,
            settings: ClientConnectionSettings,
            log: LoggingAdapter): Graph[ClientShape, Unit] = {
    import settings._

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpResponseParser(parserSettings, HttpHeaderParser(parserSettings) { info ⇒
      if (parserSettings.illegalHeaderWarnings)
        logParsingError(info withSummaryPrepended "Illegal response header", log, parserSettings.errorLoggingVerbosity)
    })

    val requestRendererFactory = new HttpRequestRendererFactory(userAgentHeader, requestHeaderSizeHint, log)

    val requestRendering: Flow[HttpRequest, ByteString, Unit] = Flow[HttpRequest]
      .map(RequestRenderingContext(_, remoteAddress))
      .via(Flow[RequestRenderingContext].transform(() ⇒ requestRendererFactory.newRenderer).named("renderer"))
      .flatten(FlattenStrategy.concat)

    val methodBypass = Flow[HttpRequest].map(_.method)

    import ParserOutput._
    val responsePrep = Flow[List[ResponseOutput]]
      .transform(recover { case x: ResponseParsingError ⇒ x.error :: Nil }) // FIXME after #16565
      .mapConcat(identityFunc)
      .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
      .headAndTail
      .collect {
        case (ResponseStart(statusCode, protocol, headers, createEntity, _), entityParts) ⇒
          HttpResponse(statusCode, headers, createEntity(entityParts), protocol)
        case (MessageStartError(_, info), _) ⇒ throw IllegalResponseException(info)
      }

    FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._
      val methodBypassFanout = b.add(Broadcast[HttpRequest](2))
      val responseParsingMerge = b.add(new ResponseParsingMerge(rootParser))

      val terminationFanout = b.add(Broadcast[HttpResponse](2))
      val terminationMerge = b.add(new TerminationMerge)

      val logger = Flow[ByteString].transform(() ⇒ errorLogger(log, "Outgoing request stream error")).named("errorLogger")
      val bytesOut = (terminationMerge.out ~> requestRendering.via(logger)).outlet

      val bytesIn = responseParsingMerge.in0

      methodBypassFanout.out(0) ~> terminationMerge.in0

      methodBypassFanout.out(1) ~> methodBypass ~> responseParsingMerge.in1

      responseParsingMerge.out ~> responsePrep ~> terminationFanout.in
      terminationFanout.out(0) ~> terminationMerge.in1

      BidiShape[HttpRequest, ByteString, ByteString, HttpResponse](
        methodBypassFanout.in,
        bytesOut,
        bytesIn,
        terminationFanout.out(1))
    }
  }

  // a simple merge stage that simply forwards its first input and ignores its second input
  // (the terminationBackchannelInput), but applies a special completion handling
  class TerminationMerge
    extends FlexiMerge[HttpRequest, FanInShape2[HttpRequest, HttpResponse, HttpRequest]](new FanInShape2("TerminationMerge"), OperationAttributes.name("TerminationMerge")) {
    import FlexiMerge._

    def createMergeLogic(p: PortT) = new MergeLogic[HttpRequest] {

      val requestInput = p.in0
      val terminationBackchannelInput = p.in1

      override def initialState = State[Any](ReadAny(p)) {
        case (ctx, _, request: HttpRequest) ⇒ { ctx.emit(request); SameState }
        case _                              ⇒ SameState // simply drop all responses, we are only interested in the completion of the response input
      }

      override def initialCompletionHandling = CompletionHandling(
        onUpstreamFinish = {
          case (ctx, `requestInput`) ⇒ SameState
          case (ctx, `terminationBackchannelInput`) ⇒
            ctx.finish()
            SameState
        },
        onUpstreamFailure = defaultCompletionHandling.onUpstreamFailure)
    }
  }

  import ParserOutput._

  /**
   * A FlexiMerge that follows this logic:
   * 1. Wait on the methodBypass for the method of the request corresponding to the next response to be received
   * 2. Read from the dataInput until exactly one response has been fully received
   * 3. Go back to 1.
   */
  class ResponseParsingMerge(rootParser: HttpResponseParser)
    extends FlexiMerge[List[ResponseOutput], FanInShape2[ByteString, HttpMethod, List[ResponseOutput]]](new FanInShape2("ResponseParsingMerge"), OperationAttributes.name("ResponsePersingMerge")) {
    import FlexiMerge._

    def createMergeLogic(p: PortT) = new MergeLogic[List[ResponseOutput]] {
      val dataInput = p.in0
      val methodBypassInput = p.in1
      // each connection uses a single (private) response parser instance for all its responses
      // which builds a cache of all header instances seen on that connection
      val parser = rootParser.createShallowCopy()
      var methodBypassCompleted = false
      private val stay = (ctx: MergeLogicContext) ⇒ SameState
      private val gotoResponseReading = (ctx: MergeLogicContext) ⇒ {
        ctx.changeCompletionHandling(responseReadingCompletionHandling)
        responseReadingState
      }
      private val gotoInitial = (ctx: MergeLogicContext) ⇒ {
        if (methodBypassCompleted) {
          ctx.finish()
          SameState
        } else {
          ctx.changeCompletionHandling(initialCompletionHandling)
          initialState
        }
      }

      override val initialState: State[HttpMethod] =
        State(Read(methodBypassInput)) {
          case (ctx, _, method) ⇒
            parser.setRequestMethodForNextResponse(method)
            drainParser(parser.onPush(ByteString.empty), ctx,
              onNeedNextMethod = stay,
              onNeedMoreData = gotoResponseReading)
        }

      val responseReadingState: State[ByteString] =
        State(Read(dataInput)) {
          case (ctx, _, bytes) ⇒
            drainParser(parser.onPush(bytes), ctx,
              onNeedNextMethod = gotoInitial,
              onNeedMoreData = stay)
        }

      @tailrec def drainParser(current: ResponseOutput, ctx: MergeLogicContext,
                               onNeedNextMethod: MergeLogicContext ⇒ State[_],
                               onNeedMoreData: MergeLogicContext ⇒ State[_],
                               b: ListBuffer[ResponseOutput] = ListBuffer.empty): State[_] = {
        def emit(output: List[ResponseOutput]): Unit = if (output.nonEmpty) ctx.emit(output)
        current match {
          case NeedNextRequestMethod ⇒
            emit(b.result())
            onNeedNextMethod(ctx)
          case StreamEnd ⇒
            emit(b.result())
            ctx.finish()
            SameState
          case NeedMoreData ⇒
            emit(b.result())
            onNeedMoreData(ctx)
          case x ⇒ drainParser(parser.onPull(), ctx, onNeedNextMethod, onNeedMoreData, b += x)
        }
      }

      override val initialCompletionHandling = CompletionHandling(
        onUpstreamFinish = (ctx, _) ⇒ { ctx.finish(); SameState },
        onUpstreamFailure = defaultCompletionHandling.onUpstreamFailure)

      val responseReadingCompletionHandling = CompletionHandling(
        onUpstreamFinish = {
          case (ctx, `methodBypassInput`) ⇒
            methodBypassCompleted = true
            SameState
          case (ctx, `dataInput`) ⇒
            if (parser.onUpstreamFinish()) {
              ctx.finish()
            } else {
              // not pretty but because the FlexiMerge doesn't let us emit from here (#16565)
              // we need to funnel the error through the error channel
              ctx.fail(new ResponseParsingError(parser.onPull().asInstanceOf[ErrorOutput]))
            }
            SameState
        },
        onUpstreamFailure = defaultCompletionHandling.onUpstreamFailure)
    }
  }

  private class ResponseParsingError(val error: ErrorOutput) extends RuntimeException

  // TODO: remove after #16394 is cleared
  def recover[A, B >: A](pf: PartialFunction[Throwable, B]): () ⇒ PushPullStage[A, B] = {
    val stage = new PushPullStage[A, B] {
      var recovery: Option[B] = None
      def onPush(elem: A, ctx: Context[B]): SyncDirective = ctx.push(elem)
      def onPull(ctx: Context[B]): SyncDirective = recovery match {
        case None    ⇒ ctx.pull()
        case Some(x) ⇒ { recovery = null; ctx.push(x) }
        case null    ⇒ ctx.finish()
      }
      override def onUpstreamFailure(cause: Throwable, ctx: Context[B]): TerminationDirective =
        if (pf isDefinedAt cause) {
          recovery = Some(pf(cause))
          ctx.absorbTermination()
        } else super.onUpstreamFailure(cause, ctx)
    }
    () ⇒ stage
  }
}
