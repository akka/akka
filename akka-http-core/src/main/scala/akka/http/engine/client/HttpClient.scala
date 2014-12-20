/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.stream.stage._
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.FlattenStrategy
import akka.stream.scaladsl._
import akka.stream.scaladsl.OperationAttributes._
import akka.http.model.{ IllegalResponseException, HttpMethod, HttpRequest, HttpResponse }
import akka.http.engine.rendering.{ RequestRenderingContext, HttpRequestRendererFactory }
import akka.http.engine.parsing.{ ParserOutput, HttpHeaderParser, HttpResponseParser }
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

    /*
      Basic Stream Setup
      ==================
    
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

    val requestIn = UndefinedSource[HttpRequest]
    val responseOut = UndefinedSink[HttpResponse]

    val methodBypassFanout = Broadcast[HttpRequest]
    val responseParsingMerge = new ResponseParsingMerge(rootParser)

    val terminationFanout = Broadcast[HttpResponse]
    val terminationMerge = new TerminationMerge

    val requestRendering = Flow[HttpRequest]
      .map(RequestRenderingContext(_, remoteAddress))
      .section(name("renderer"))(_.transform(() ⇒ requestRendererFactory.newRenderer))
      .flatten(FlattenStrategy.concat)

    val transportFlow = Flow[ByteString]
      .section(name("errorLogger"))(_.transform(() ⇒ errorLogger(log, "Outgoing request stream error")))
      .via(transport)

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

    import FlowGraphImplicits._

    Flow() { implicit b ⇒
      requestIn ~> methodBypassFanout ~> terminationMerge.requestInput ~> requestRendering ~> transportFlow ~>
        responseParsingMerge.dataInput ~> responsePrep ~> terminationFanout ~> responseOut
      methodBypassFanout ~> methodBypass ~> responseParsingMerge.methodBypassInput
      terminationFanout ~> terminationMerge.terminationBackchannelInput

      b.allowCycles()

      requestIn -> responseOut
    }
  }

  // a simple merge stage that simply forwards its first input and ignores its second input
  // (the terminationBackchannelInput), but applies a special completion handling
  class TerminationMerge extends FlexiMerge[HttpRequest] {
    import FlexiMerge._
    val requestInput = createInputPort[HttpRequest]()
    val terminationBackchannelInput = createInputPort[HttpResponse]()

    def createMergeLogic() = new MergeLogic[HttpRequest] {
      override def inputHandles(inputCount: Int) = {
        require(inputCount == 2, s"TerminationMerge must have 2 connected inputs, was $inputCount")
        Vector(requestInput, terminationBackchannelInput)
      }

      override def initialState = State[Any](ReadAny(requestInput, terminationBackchannelInput)) {
        case (ctx, _, request: HttpRequest) ⇒ { ctx.emit(request); SameState }
        case _                              ⇒ SameState // simply drop all responses, we are only interested in the completion of the response input
      }

      override def initialCompletionHandling = CompletionHandling(
        onComplete = {
          case (ctx, `requestInput`) ⇒ SameState
          case (ctx, `terminationBackchannelInput`) ⇒
            ctx.complete()
            SameState
        },
        onError = defaultCompletionHandling.onError)
    }
  }

  import ParserOutput._

  /**
   * A FlexiMerge that follows this logic:
   * 1. Wait on the methodBypass for the method of the request corresponding to the next response to be received
   * 2. Read from the dataInput until exactly one response has been fully received
   * 3. Go back to 1.
   */
  class ResponseParsingMerge(rootParser: HttpResponseParser) extends FlexiMerge[List[ResponseOutput]] {
    import FlexiMerge._
    val dataInput = createInputPort[ByteString]()
    val methodBypassInput = createInputPort[HttpMethod]()

    def createMergeLogic() = new MergeLogic[List[ResponseOutput]] {
      // each connection uses a single (private) response parser instance for all its responses
      // which builds a cache of all header instances seen on that connection
      val parser = rootParser.createShallowCopy()
      var methodBypassCompleted = false

      override def inputHandles(inputCount: Int) = {
        require(inputCount == 2, s"ResponseParsingMerge must have 2 connected inputs, was $inputCount")
        Vector(dataInput, methodBypassInput)
      }

      private val stay = (ctx: MergeLogicContext) ⇒ SameState
      private val gotoResponseReading = (ctx: MergeLogicContext) ⇒ {
        ctx.changeCompletionHandling(responseReadingCompletionHandling)
        responseReadingState
      }
      private val gotoInitial = (ctx: MergeLogicContext) ⇒ {
        if (methodBypassCompleted) {
          ctx.complete()
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
            ctx.complete()
            SameState
          case NeedMoreData ⇒
            emit(b.result())
            onNeedMoreData(ctx)
          case x ⇒ drainParser(parser.onPull(), ctx, onNeedNextMethod, onNeedMoreData, b += x)
        }
      }

      override val initialCompletionHandling = CompletionHandling(
        onComplete = (ctx, _) ⇒ { ctx.complete(); SameState },
        onError = defaultCompletionHandling.onError)

      val responseReadingCompletionHandling = CompletionHandling(
        onComplete = {
          case (ctx, `methodBypassInput`) ⇒
            methodBypassCompleted = true
            SameState
          case (ctx, `dataInput`) ⇒
            if (parser.onUpstreamFinish()) {
              ctx.complete()
            } else {
              // not pretty but because the FlexiMerge doesn't let us emit from here (#16565)
              // we need to funnel the error through the error channel
              ctx.error(new ResponseParsingError(parser.onPull().asInstanceOf[ErrorOutput]))
            }
            SameState
        },
        onError = defaultCompletionHandling.onError)
    }
  }

  private class ResponseParsingError(val error: ErrorOutput) extends RuntimeException

  // TODO: remove after #16394 is cleared
  def recover[A, B >: A](pf: PartialFunction[Throwable, B]): () ⇒ PushPullStage[A, B] = {
    val stage = new PushPullStage[A, B] {
      var recovery: Option[B] = None
      def onPush(elem: A, ctx: Context[B]): Directive = ctx.push(elem)
      def onPull(ctx: Context[B]): Directive = recovery match {
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