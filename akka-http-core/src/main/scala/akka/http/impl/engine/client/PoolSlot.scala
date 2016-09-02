/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.impl.engine.client.PoolSlot.SlotEvent.{ ConnectedEagerly, RetryRequest }
import akka.http.scaladsl.model.headers.Connection
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.GraphStageLogic.EagerTerminateOutput
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

import scala.concurrent.Future
import scala.language.existentials
import scala.util.{ Failure, Success }
import scala.collection.JavaConverters._

private object PoolSlot {
  import PoolFlow.{ RequestContext, ResponseContext }

  sealed trait RawSlotEvent
  sealed trait SlotEvent extends RawSlotEvent
  object SlotEvent {
    final case class RequestCompletedFuture(future: Future[RequestCompleted]) extends RawSlotEvent
    final case class RetryRequest(rc: RequestContext) extends RawSlotEvent
    final case class RequestCompleted(slotIx: Int) extends SlotEvent
    final case class Disconnected(slotIx: Int, failedRequests: Int) extends SlotEvent
    /**
     * Slot with id "slotIx" has responded to request from PoolConductor and connected immediately
     * Ordinary connections from slots don't produce this event
     */
    final case class ConnectedEagerly(slotIx: Int) extends SlotEvent
  }

  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any])(implicit m: Materializer): Graph[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent], Any] = {
    val log = ActorMaterializerHelper.downcast(m).logger
    new SlotProcessor(slotIx, connectionFlow, log)
  }

  /**
   * To the outside it provides a stable flow stage, consuming `SlotCommand` instances on its
   * input side and producing `ResponseContext` and `RawSlotEvent` instances on its outputs.
   * The given `connectionFlow` is materialized into a running flow whenever required.
   * Completion and errors from the connection are not surfaced to the outside (unless we are
   * shutting down completely).
   */
  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any], log: LoggingAdapter)(implicit fm: Materializer)
    extends GraphStage[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent]] {

    val in: Inlet[SlotCommand] = Inlet("SlotProcessor.in")
    val out0: Outlet[ResponseContext] = Outlet("SlotProcessor.responsesOut")
    val out1: Outlet[RawSlotEvent] = Outlet("SlotProcessor.eventsOut")

    override def shape: FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent] = new FanOutShape2(in, out0, out1)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler {
      private val inflightRequests = new java.util.ArrayDeque[RequestContext]()

      private var connectionFlowSource: SubSourceOutlet[HttpRequest] = _
      private var connectionFlowSink: SubSinkInlet[HttpResponse] = _

      private var isConnected = false

      override def preStart(): Unit = pull(in)

      def disconnect(retries: List[RetryRequest] = Nil) = {
        connectionFlowSource.complete()
        if (isConnected) {
          isConnected = false
          emitMultiple(out1, SlotEvent.Disconnected(slotIx, retries.size) :: retries, () ⇒ pull(in))
        }
      }

      // SourceOutlet is connected to the connectionFlow's inlet, when the connectionFlow
      // completes (e.g. connection closed) complete the subflow and emit the Disconnected event
      private val connectionOutFlowHandler = new OutHandler {
        override def onPull(): Unit = ()
        override def onDownstreamFinish(): Unit = disconnect()
      }

      // SinkInlet is connected to the connectionFlow's outlet, an upstream
      // complete indicates the remote has shutdown cleanly, a failure is
      // abnormal termination/connection refused.  Successful requests
      // will show up in `onPush`
      private val connectionInFlowHandler = new InHandler {
        override def onPush(): Unit = {
          val response: HttpResponse = connectionFlowSink.grab()

          val requestContext = inflightRequests.pop
          val (entity, whenCompleted) = HttpEntity.captureTermination(response.entity)
          import fm.executionContext
          push(out0, ResponseContext(requestContext, Success(response withEntity entity)))
          push(out1, SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx))))

          // the connectionFlow uses One2OneBidiFlow so its strictly one in, one out and
          // since any request can close the connection we can't stack them
          if (response.header[Connection].forall(!_.hasClose)) {
            pull(in)
          }

          connectionFlowSink.pull()
        }

        override def onUpstreamFinish(): Unit = disconnect()

        override def onUpstreamFailure(ex: Throwable): Unit = {
          val retryRequests: List[RetryRequest] = inflightRequests.iterator().asScala.filter(_.retriesLeft > 0)
            .map(rc ⇒ SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1))).toList
          val failures = inflightRequests.iterator().asScala.filter(_.retriesLeft == 0).map(rc ⇒ ResponseContext(rc, Failure(ex))).toList

          inflightRequests.clear()

          emitMultiple(out0, failures)
          disconnect(retryRequests)
        }
      }

      override def onPush(): Unit = {
        def createSubSourceOutlets() = {
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          isConnected = true
        }

        grab(in) match {
          case ConnectEagerlyCommand ⇒
            if (!isConnected) {
              createSubSourceOutlets()

              Source.fromGraph(connectionFlowSource.source)
                .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

              connectionFlowSink.pull()
            }

            emit(out1, ConnectedEagerly(slotIx), () ⇒ pull(in))

          case DispatchCommand(rc: RequestContext) ⇒
            if (isConnected) {
              inflightRequests.add(rc)
              connectionFlowSource.push(rc.request)
            } else {
              createSubSourceOutlets()

              inflightRequests.add(rc)

              Source.single(rc.request).concat(Source.fromGraph(connectionFlowSource.source))
                .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

              connectionFlowSink.pull()
            }
        }
      }

      setHandler(in, this)
      setHandler(out0, EagerTerminateOutput)
      setHandler(out1, EagerTerminateOutput)
    }

  }

  final class UnexpectedDisconnectException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
    def this(msg: String) = this(msg, null)
  }
}
