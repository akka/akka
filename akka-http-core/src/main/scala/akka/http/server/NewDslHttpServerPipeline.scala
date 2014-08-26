package akka.http.server

import akka.event.LoggingAdapter
import akka.http.Http
import akka.http.model.{ ErrorInfo, HttpRequest, HttpResponse }
import akka.http.parsing.HttpRequestParser
import akka.http.parsing.ParserOutput._
import akka.http.rendering.ResponseRenderingContext
import akka.stream.dsl._
import akka.stream.io.StreamTcp
import akka.stream.{ FlowMaterializer, Transformer }
import akka.util.ByteString

class NewDslHttpServerPipeline(settings: ServerSettings,
                               materializer: FlowMaterializer,
                               log: LoggingAdapter) {

  import akka.http.server.NewDslHttpServerPipeline._

  val rootParser = new HttpRequestParser(settings.parserSettings, settings.rawRequestUriHeader, materializer)()
  val warnOnIllegalHeader: ErrorInfo ⇒ Unit = errorInfo ⇒
    if (settings.parserSettings.illegalHeaderWarnings)
      log.warning(errorInfo.withSummaryPrepended("Illegal request header").formatPretty)

  val responseRendererFactory = new {
    def newRenderer: Transformer[ResponseRenderingContext, OpenOutputFlow[ByteString, ByteString]] = ???
  }

  /**
   * Flow graph:
   *
   * tcpConn.inputStream ---> requestFlowBeforeBroadcast -+-> requestFlowAfterBroadcast ---> Publisher[HttpRequest]
   *                                                      |
   *                                                      \-> applicationBypassFlow -\
   *                                                                                 |
   *                          Subscriber[HttpResponse] ---> responseFlowBeforeMerge -+-> responseFlowAfterMerge --> tcpConn.outputStream
   */
  def apply(tcpConn: StreamTcp.IncomingTcpConnection) = {

    val broadcast = Broadcast[(RequestOutput, OpenOutputFlow[RequestOutput, RequestOutput])]()
    val merge = Merge[MessageStart, HttpResponse, Any]()

    val requestFlowBeforeBroadcast: ClosedFlow[ByteString, (RequestOutput, OpenOutputFlow[RequestOutput, RequestOutput])] =
      From(tcpConn.inputStream)
        .transform(rootParser.copyWith(warnOnIllegalHeader))
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x == MessageEnd)
        .headAndTail()
        .withOutput(broadcast.in)

    val applicationBypassFlow: ClosedFlow[(RequestOutput, OpenOutputFlow[RequestOutput, RequestOutput]), MessageStart] =
      From[(RequestOutput, OpenOutputFlow[RequestOutput, RequestOutput])]
        .withInput(broadcast.out1)
        .collect[MessageStart with RequestOutput] { case (x: MessageStart, _) ⇒ x }
        .withOutput(merge.in1)

    val requestPublisher = PublisherOut[HttpRequest]()
    val requestFlowAfterBroadcast: ClosedFlow[(RequestOutput, OpenOutputFlow[RequestOutput, RequestOutput]), HttpRequest] =
      From[(RequestOutput, OpenOutputFlow[RequestOutput, RequestOutput])]
        .withInput(broadcast.out2)
        .collect {
          case (RequestStart(method, uri, protocol, headers, createEntity, _), entityParts) ⇒
            val effectiveUri = HttpRequest.effectiveUri(uri, headers, securedConnection = false, settings.defaultHostHeader)
            val publisher = PublisherOut[RequestOutput]()
            val flow = entityParts.withOutput(publisher)
            HttpRequest(method, effectiveUri, headers, createEntity(publisher.publisher), protocol)
        }
        .withOutput(requestPublisher)

    val responseSubscriber = SubscriberIn[HttpResponse]()
    val responseFlowBeforeMerge: ClosedFlow[HttpResponse, HttpResponse] =
      From[HttpResponse]
        .withInput(responseSubscriber)
        .withOutput(merge.in2)

    val responseFlowAfterMerge: ClosedFlow[Any, ByteString] =
      From[Any]
        .withInput(merge.out)
        .transform(applyApplicationBypass)
        .transform(responseRendererFactory.newRenderer)
        .flatten(FlattenStrategy.concatOpenOutputFlow)
        .transform(errorLogger(log, "Outgoing response stream error"))
        .withOutput(SubscriberOut(tcpConn.outputStream))

    Http.IncomingConnection(tcpConn.remoteAddress, requestPublisher.publisher, responseSubscriber.subscriber)
  }

  def applyApplicationBypass: Transformer[Any, ResponseRenderingContext] = ???

  private[http] def errorLogger(log: LoggingAdapter, msg: String): Transformer[ByteString, ByteString] = ???
}

object NewDslHttpServerPipeline {

  /**
   * FIXME: We can't use `HasOpenOutput` here, because conversion would convert either `OpenFlow`
   * or `OpenOutputFlow` to `HasOpenOutput`.
   *
   * Therefore we need two separate conversions, one for `OpeFlow` another for `OpenOutputFlow`.
   */
  implicit class OpenOutputFlowWithHeadAndTail[In, InnerIn, InnerOut](val underlying: OpenOutputFlow[In, OpenOutputFlow[InnerIn, InnerOut]]) extends AnyVal {
    def headAndTail(): OpenOutputFlow[In, (InnerOut, OpenOutputFlow[InnerOut, InnerOut])] = {
      val flow: OpenFlow[OpenOutputFlow[InnerIn, InnerOut], OpenOutputFlow[InnerIn, (InnerOut, OpenOutputFlow[InnerOut, InnerOut])]] =
        From[OpenOutputFlow[InnerIn, InnerOut]]
          .map { f ⇒
            f.prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) }
          }

      val flattened: OpenFlow[OpenOutputFlow[InnerIn, InnerOut], (InnerOut, OpenOutputFlow[InnerOut, InnerOut])] =
        flow.flatten(FlattenStrategy.concatOpenOutputFlow)

      underlying.append(flattened)
    }
  }
}
