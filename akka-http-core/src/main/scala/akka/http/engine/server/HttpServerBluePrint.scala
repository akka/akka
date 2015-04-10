/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import scala.util.control.NonFatal
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.actor.{ ActorRef, Props }
import akka.stream.stage.PushPullStage
import akka.stream.OperationAttributes._
import akka.stream.scaladsl._
import akka.stream._
import akka.http.engine.parsing._
import akka.http.engine.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.engine.TokenSourceActor
import akka.http.model._
import akka.http.util._
import ParserOutput._

/**
 * INTERNAL API
 */
private[http] object HttpServerBluePrint {

  type ServerShape = BidiShape[HttpResponse, ByteString, ByteString, HttpRequest]

  def apply(settings: ServerSettings, log: LoggingAdapter)(implicit mat: FlowMaterializer): Graph[ServerShape, Unit] = {
    import settings._

    // the initial header parser we initially use for every connection,
    // will not be mutated, all "shared copy" parsers copy on first-write into the header cache
    val rootParser = new HttpRequestParser(parserSettings, rawRequestUriHeader,
      HttpHeaderParser(parserSettings) { info ⇒
        if (parserSettings.illegalHeaderWarnings)
          logParsingError(info withSummaryPrepended "Illegal request header", log, parserSettings.errorLoggingVerbosity)
      })

    val responseRendererFactory = new HttpResponseRendererFactory(serverHeader, responseHeaderSizeHint, log)

    @volatile var oneHundredContinueRef: Option[ActorRef] = None // FIXME: unnecessary after fixing #16168
    val oneHundredContinueSource = Source.actorPublisher[OneHundredContinue.type] {
      Props {
        val actor = new TokenSourceActor(OneHundredContinue)
        oneHundredContinueRef = Some(actor.context.self)
        actor
      }
    }

    val requestParsingFlow = Flow[ByteString].transform(() ⇒
      // each connection uses a single (private) request parser instance for all its requests
      // which builds a cache of all header instances seen on that connection
      rootParser.createShallowCopy(() ⇒ oneHundredContinueRef).stage).named("rootParser")

    val requestPreparation =
      Flow[RequestOutput]
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
        .headAndTail
        .map {
          case (RequestStart(method, uri, protocol, headers, createEntity, _, _), entityParts) ⇒
            val effectiveUri = HttpRequest.effectiveUri(uri, headers, securedConnection = false, defaultHostHeader)
            val effectiveMethod = if (method == HttpMethods.HEAD && transparentHeadRequests) HttpMethods.GET else method
            HttpRequest(effectiveMethod, effectiveUri, headers, createEntity(entityParts), protocol)
          case (_, src) ⇒ src.runWith(Sink.ignore)
        }.collect {
          case r: HttpRequest ⇒ r
        }.buffer(1, OverflowStrategy.backpressure)
    // FIXME #16583 it is unclear why this is needed, some element probably does not propagate demand eagerly enough
    // the failing test would be HttpServerSpec

    // we need to make sure that only one element per incoming request is queueing up in front of
    // the bypassMerge.bypassInput. Otherwise the rising backpressure against the bypassFanout
    // would eventually prevent us from reading the remaining request chunks from the transportIn
    val bypass = Flow[RequestOutput].filter {
      case (_: RequestStart | _: MessageStartError) ⇒ true
      case _                                        ⇒ false
    }

    val rendererPipeline =
      Flow[ResponseRenderingContext]
        .via(Flow[ResponseRenderingContext].transform(() ⇒ new ErrorsTo500ResponseRecovery(log)).named("recover")) // FIXME: simplify after #16394 is closed
        .via(Flow[ResponseRenderingContext].transform(() ⇒ responseRendererFactory.newRenderer).named("renderer"))
        .flatten(FlattenStrategy.concat)
        .via(Flow[ByteString].transform(() ⇒ errorLogger(log, "Outgoing response stream error")).named("errorLogger"))

    FlowGraph.partial(requestParsingFlow, rendererPipeline)(Keep.right) { implicit b ⇒
      (requestParsing, renderer) ⇒
        import FlowGraph.Implicits._

        val bypassFanout = b.add(Broadcast[RequestOutput](2, OperationAttributes.name("bypassFanout")))
        val bypassMerge = b.add(new BypassMerge(settings, log))
        val bypassInput = bypassMerge.in0
        val bypassOneHundredContinueInput = bypassMerge.in1
        val bypassApplicationInput = bypassMerge.in2

        requestParsing.outlet ~> bypassFanout.in
        bypassMerge.out ~> renderer.inlet
        val requestsIn = (bypassFanout.out(0) ~> requestPreparation).outlet

        bypassFanout.out(1) ~> bypass ~> bypassInput
        oneHundredContinueSource ~> bypassOneHundredContinueInput

        BidiShape[HttpResponse, ByteString, ByteString, HttpRequest](
          bypassApplicationInput,
          renderer.outlet,
          requestParsing.inlet,
          requestsIn)
    }
  }

  class BypassMerge(settings: ServerSettings, log: LoggingAdapter)
    extends FlexiMerge[ResponseRenderingContext, FanInShape3[RequestOutput, OneHundredContinue.type, HttpResponse, ResponseRenderingContext]](new FanInShape3("BypassMerge"), OperationAttributes.name("BypassMerge")) {
    import FlexiMerge._

    def createMergeLogic(p: PortT) = new MergeLogic[ResponseRenderingContext] {
      var requestStart: RequestStart = _

      val bypassInput: Inlet[RequestOutput] = p.in0
      val oneHundredContinueInput: Inlet[OneHundredContinue.type] = p.in1
      val applicationInput: Inlet[HttpResponse] = p.in2

      override val initialState: State[RequestOutput] = State[RequestOutput](Read(bypassInput)) {
        case (ctx, _, requestStart: RequestStart) ⇒
          this.requestStart = requestStart
          ctx.changeCompletionHandling(waitingForApplicationResponseCompletionHandling)
          waitingForApplicationResponse
        case (ctx, _, MessageStartError(status, info)) ⇒ finishWithError(ctx, status, info)
        case _                                         ⇒ throw new IllegalStateException
      }

      override val initialCompletionHandling = eagerClose

      val waitingForApplicationResponse =
        State[Any](ReadAny(oneHundredContinueInput.asInstanceOf[Inlet[Any]] :: applicationInput.asInstanceOf[Inlet[Any]] :: Nil)) {
          case (ctx, _, response: HttpResponse) ⇒
            // see the comment on [[OneHundredContinue]] for an explanation of the closing logic here (and more)
            val close = requestStart.closeRequested || requestStart.expect100ContinueResponsePending
            ctx.emit(ResponseRenderingContext(response, requestStart.method, requestStart.protocol, close))
            if (close) finish(ctx) else {
              ctx.changeCompletionHandling(eagerClose)
              initialState
            }

          case (ctx, _, OneHundredContinue) ⇒
            assert(requestStart.expect100ContinueResponsePending)
            ctx.emit(ResponseRenderingContext(HttpResponse(StatusCodes.Continue)))
            requestStart = requestStart.copy(expect100ContinueResponsePending = false)
            SameState
        }

      val waitingForApplicationResponseCompletionHandling = CompletionHandling(
        onUpstreamFinish = {
          case (ctx, `bypassInput`) ⇒ { requestStart = requestStart.copy(closeRequested = true); SameState }
          case (ctx, _)             ⇒ { ctx.finish(); SameState }
        },
        onUpstreamFailure = {
          case (ctx, _, EntityStreamException(errorInfo)) ⇒
            // the application has forwarded a request entity stream error to the response stream
            finishWithError(ctx, StatusCodes.BadRequest, errorInfo)
          case (ctx, _, error) ⇒ { ctx.fail(error); SameState }
        })

      def finishWithError(ctx: MergeLogicContextBase, status: StatusCode, info: ErrorInfo): State[Any] = {
        logParsingError(info withSummaryPrepended s"Illegal request, responding with status '$status'",
          log, settings.parserSettings.errorLoggingVerbosity)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        // FIXME this is a workaround that is supposed to be solved by issue #16753
        ctx match {
          case fullCtx: MergeLogicContext ⇒
            // note that this will throw IllegalArgumentException if no demand available
            fullCtx.emit(ResponseRenderingContext(HttpResponse(status, entity = msg), closeRequested = true))
          case other ⇒ throw new IllegalStateException(s"Unexpected MergeLogicContext [${other.getClass.getName}]")
        }
        finish(ctx)
      }

      def finish(ctx: MergeLogicContextBase): State[Any] = {
        ctx.finish() // shouldn't this return a `State` rather than `Unit`?
        SameState // it seems weird to stay in the same state after completion
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
}
