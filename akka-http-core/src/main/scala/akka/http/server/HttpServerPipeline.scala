/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import org.reactivestreams.api.Producer
import scala.concurrent.ExecutionContext
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.stream.io.StreamTcp
import akka.stream.{ Transformer, FlowMaterializer }
import akka.stream.scaladsl.{ Flow, Duct }
import akka.http.parsing.HttpRequestParser
import akka.http.rendering.{ ResponseRenderingContext, HttpResponseRendererFactory }
import akka.http.model.{ StatusCode, ErrorInfo, HttpRequest, HttpResponse }
import akka.http.parsing.ParserOutput._
import akka.http.Http

private[http] class HttpServerPipeline(settings: ServerSettings,
                                       materializer: FlowMaterializer,
                                       log: LoggingAdapter)(implicit ec: ExecutionContext)
  extends (StreamTcp.IncomingTcpConnection ⇒ Http.IncomingConnection) {
  import HttpServerPipeline._

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader, materializer)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new HttpResponseRendererFactory(settings.serverHeader, settings.chunklessStreaming,
    settings.responseHeaderSizeHint, materializer, log)

  def apply(tcpConn: StreamTcp.IncomingTcpConnection): Http.IncomingConnection = {
    val (applicationBypassConsumer, applicationBypassProducer) =
      Duct[(RequestOutput, Producer[RequestOutput])]
        .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }
        .build(materializer)

    val requestProducer =
      Flow(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .splitWhen(_.isInstanceOf[MessageStart])
        .headAndTail(materializer)
        .tee(applicationBypassConsumer)
        .collect { case (x: RequestStart, entityParts) ⇒ HttpServerPipeline.constructRequest(x, entityParts) }
        .toProducer(materializer)

    val responseConsumer =
      Duct[HttpResponse]
        .merge(applicationBypassProducer)
        .transform(applyApplicationBypass)
        .transform(responseRendererFactory.newRenderer)
        .concatAll
        .transform {
          new Transformer[ByteString, ByteString] {
            def onNext(element: ByteString) = element :: Nil
            override def onError(cause: Throwable): Unit = log.error(cause, "Response stream error")
          }
        }.produceTo(materializer, tcpConn.outputStream)

    Http.IncomingConnection(tcpConn.remoteAddress, requestProducer, responseConsumer)
  }

  /**
   * Combines the HttpResponse coming in from the application with the ParserOutput.RequestStart
   * produced by the request parser into a ResponseRenderingContext.
   * If the parser produced a ParserOutput.ParseError the error response is immediately dispatched to downstream.
   */
  def applyApplicationBypass =
    new Transformer[Any, ResponseRenderingContext] {
      var applicationResponse: HttpResponse = _
      var requestStart: RequestStart = _

      def onNext(elem: Any) = elem match {
        case response: HttpResponse ⇒
          requestStart match {
            case null ⇒
              applicationResponse = response
              Nil
            case x: RequestStart ⇒
              requestStart = null
              dispatch(x, response)
          }

        case requestStart: RequestStart ⇒
          applicationResponse match {
            case null ⇒
              this.requestStart = requestStart
              Nil
            case response ⇒
              applicationResponse = null
              dispatch(requestStart, response)
          }

        case ParseError(status, info) ⇒ errorResponse(status, info) :: Nil
      }

      def dispatch(requestStart: RequestStart, response: HttpResponse): List[ResponseRenderingContext] = {
        import requestStart._
        ResponseRenderingContext(response, method, protocol, closeAfterResponseCompletion) :: Nil
      }

      def errorResponse(status: StatusCode, info: ErrorInfo): ResponseRenderingContext = {
        log.warning("Illegal request, responding with status '{}': {}", status, info.formatPretty)
        val msg = if (settings.verboseErrorMessages) info.formatPretty else info.summary
        ResponseRenderingContext(HttpResponse(status, msg), closeAfterResponseCompletion = true)
      }
    }
}

private[http] object HttpServerPipeline {
  def constructRequest(requestStart: RequestStart, entityParts: Producer[RequestOutput]): HttpRequest = {
    import requestStart._
    HttpRequest(method, uri, headers, createEntity(entityParts), protocol)
  }

  implicit class FlowWithHeadAndTail[T](val underlying: Flow[Producer[T]]) {
    def headAndTail(materializer: FlowMaterializer): Flow[(T, Producer[T])] = {
      val duct: Duct[Producer[T], (T, Producer[T])] =
        Duct[Producer[T]].map { p ⇒
          val (ductIn, tailStream) = Duct[T].drop(1).build(materializer)
          Flow(p).tee(ductIn).take(1).map(_ -> tailStream).toProducer(materializer)
        }.concatAll
      val (headAndTailC, headAndTailP) = duct.build(materializer)
      underlying.produceTo(materializer, headAndTailC)
      Flow(headAndTailP)
    }
  }
}