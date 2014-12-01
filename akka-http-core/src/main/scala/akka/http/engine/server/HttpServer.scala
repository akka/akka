/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import akka.actor.{ ActorRef, Props }
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.stage.PushPullStage
import akka.stream.FlattenStrategy
import akka.stream.scaladsl._
import akka.http.engine.parsing.{ HttpHeaderParser, HttpRequestParser }
import akka.http.engine.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.engine.parsing.ParserOutput._
import akka.http.model._
import akka.http.util._
import akka.http.Http

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[http] object HttpServer {

  def serverFlowToTransport(serverFlow: Flow[HttpRequest, HttpResponse],
                            settings: ServerSettings,
                            log: LoggingAdapter): Flow[ByteString, ByteString] = {

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpRequestParser(
      settings.parserSettings,
      settings.rawRequestUriHeader,
      HttpHeaderParser(settings.parserSettings) { errorInfo ⇒
        if (settings.parserSettings.illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)
      })

    val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.responseHeaderSizeHint, log)

    @volatile var oneHundredContinueRef: Option[ActorRef] = None // FIXME: unnecessary after fixing #16168
    val oneHundredContinueSource = Source[OneHundredContinue.type] {
      Props {
        val actor = new OneHundredContinueSourceActor
        oneHundredContinueRef = Some(actor.context.self)
        actor
      }
    }

    val bypassFanout = Broadcast[RequestOutput]("bypassFanout")
    val bypassMerge = new BypassMerge(settings, log)

    val requestParsing = Flow[ByteString].transform("rootParser", () ⇒
      // each connection uses a single (private) request parser instance for all its requests
      // which builds a cache of all header instances seen on that connection
      rootParser.createShallowCopy(() ⇒ oneHundredContinueRef))

    val requestPreparation =
      Flow[RequestOutput]
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
        .headAndTail
        .collect {
          case (RequestStart(method, uri, protocol, headers, createEntity, _, _), entityParts) ⇒
            val effectiveUri = HttpRequest.effectiveUri(uri, headers, securedConnection = false, settings.defaultHostHeader)
            val effectiveMethod = if (method == HttpMethods.HEAD && settings.transparentHeadRequests) HttpMethods.GET else method
            HttpRequest(effectiveMethod, effectiveUri, headers, createEntity(entityParts), protocol)
        }

    val rendererPipeline =
      Flow[ResponseRenderingContext]
        .transform("recover", () ⇒ new ErrorsTo500ResponseRecovery(log)) // FIXME: simplify after #16394 is closed
        .transform("renderer", () ⇒ responseRendererFactory.newRenderer)
        .flatten(FlattenStrategy.concat)
        .transform("errorLogger", () ⇒ errorLogger(log, "Outgoing response stream error"))

    val transportIn = UndefinedSource[ByteString]
    val transportOut = UndefinedSink[ByteString]

    import FlowGraphImplicits._

    Flow() { implicit b ⇒
      //FIXME: the graph is unnecessary after fixing #15957
      transportIn ~> requestParsing ~> bypassFanout ~> requestPreparation ~> serverFlow ~> bypassMerge.applicationInput ~> rendererPipeline ~> transportOut
      bypassFanout ~> bypassMerge.bypassInput
      oneHundredContinueSource ~> bypassMerge.oneHundredContinueInput

      b.allowCycles()

      transportIn -> transportOut
    }
  }

  class BypassMerge(settings: ServerSettings, log: LoggingAdapter) extends FlexiMerge[ResponseRenderingContext]("BypassMerge") {
    import FlexiMerge._
    val bypassInput = createInputPort[RequestOutput]()
    val oneHundredContinueInput = createInputPort[OneHundredContinue.type]()
    val applicationInput = createInputPort[HttpResponse]()

    def createMergeLogic() = new MergeLogic[ResponseRenderingContext] {
      override def inputHandles(inputCount: Int) = {
        require(inputCount == 3, s"BypassMerge must have 3 connected inputs, was $inputCount")
        Vector(bypassInput, oneHundredContinueInput, applicationInput)
      }

      override val initialState = State[Any](Read(bypassInput)) {
        case (ctx, _, requestStart: RequestStart)      ⇒ waitingForApplicationResponse(requestStart)
        case (ctx, _, MessageStartError(status, info)) ⇒ finishWithError(ctx, "request", status, info)
        case _                                         ⇒ SameState // drop other parser output
      }

      def waitingForApplicationResponse(requestStart: RequestStart): State[Any] =
        State[Any](ReadAny(oneHundredContinueInput, applicationInput)) {
          case (ctx, _, response: HttpResponse) ⇒
            // see the comment on [[OneHundredContinue]] for an explanation of the closing logic here (and more)
            val close = requestStart.closeAfterResponseCompletion || requestStart.expect100ContinueResponsePending
            ctx.emit(ResponseRenderingContext(response, requestStart.method, requestStart.protocol, close))
            if (close) finish(ctx) else initialState

          case (ctx, _, OneHundredContinue) ⇒
            assert(requestStart.expect100ContinueResponsePending)
            ctx.emit(ResponseRenderingContext(HttpResponse(StatusCodes.Continue)))
            waitingForApplicationResponse(requestStart.copy(expect100ContinueResponsePending = false))
        }

      override def initialCompletionHandling = CompletionHandling(
        onComplete = (ctx, _) ⇒ { ctx.complete(); SameState },
        onError = {
          case (ctx, _, error: Http.StreamException) ⇒
            // the application has forwarded a request entity stream error to the response stream
            finishWithError(ctx, "request", StatusCodes.BadRequest, error.info)
          case (ctx, _, error) ⇒
            ctx.error(error)
            SameState
        })

      def finishWithError(ctx: MergeLogicContext, target: String, status: StatusCode, info: ErrorInfo): State[Any] = {
        log.warning("Illegal {}, responding with status '{}': {}", target, status, info.formatPretty)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        ctx.emit(ResponseRenderingContext(HttpResponse(status, entity = msg), closeAfterResponseCompletion = true))
        finish(ctx)
      }

      def finish(ctx: MergeLogicContext): State[Any] = {
        ctx.complete() // shouldn't this return a `State` rather than `Unit`?
        SameState // it seems weird to stay in the same state after completion
      }
    }
  }
}

private[server] class ErrorsTo500ResponseRecovery(log: LoggingAdapter)
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
          closeAfterResponseCompletion = true)
        ctx.absorbTermination()
      case _ ⇒ ctx.fail(error)
    }
}