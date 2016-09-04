/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.impl.engine.client.PoolSlot.SlotEvent.ConnectedEagerly
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

  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any])(implicit m: Materializer): Graph[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent], Any] =
    new SlotProcessor(slotIx, connectionFlow, ActorMaterializerHelper.downcast(m).logger)

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
    val responsesOut: Outlet[ResponseContext] = Outlet("SlotProcessor.responsesOut")
    val eventsOut: Outlet[RawSlotEvent] = Outlet("SlotProcessor.eventsOut")

    override def shape: FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent] = new FanOutShape2(in, responsesOut, eventsOut)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler {
      private var firstRequest: RequestContext = _
      private val inflightRequests = new java.util.ArrayDeque[RequestContext]()

      private var connectionFlowSource: SubSourceOutlet[HttpRequest] = _
      private var connectionFlowSink: SubSinkInlet[HttpResponse] = _

      private var isConnected = false

      def disconnect(ex: Option[Throwable] = None) = {
        connectionFlowSource.complete()
        if (isConnected) {
          isConnected = false

          // if there was an error sending the request may have been sent so decrement retriesLeft
          // otherwise the downstream hasn't sent so sent them back without modifying retriesLeft
          val (retries, failures) = ex.map { fail ⇒
            val (inflightRetry, inflightFail) = inflightRequests.iterator().asScala.partition(_.retriesLeft > 0)
            val retries = inflightRetry.map(rc ⇒ SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1))).toList
            val failures = inflightFail.map(rc ⇒ ResponseContext(rc, Failure(fail))).toList
            (retries, failures)
          }.getOrElse((inflightRequests.iterator().asScala.map(rc ⇒ SlotEvent.RetryRequest(rc)).toList, Nil))

          inflightRequests.clear()

          emitMultiple(responsesOut, failures)
          emitMultiple(eventsOut, SlotEvent.Disconnected(slotIx, retries.size + failures.size) :: retries, () ⇒ if (failures.isEmpty && !hasBeenPulled(in)) pull(in))
        }
      }

      // SourceOutlet is connected to the connectionFlow's inlet, when the connectionFlow
      // completes (e.g. connection closed) complete the subflow and emit the Disconnected event
      private val connectionOutFlowHandler = new OutHandler {
        // inner stream pulls, we either give first request or pull upstream
        override def onPull(): Unit = {
          if (firstRequest != null) {
            inflightRequests.add(firstRequest)
            connectionFlowSource.push(firstRequest.request)
            firstRequest = null
          } else pull(in)
        }

        override def onDownstreamFinish(): Unit = connectionFlowSource.complete()
      }

      // SinkInlet is connected to the connectionFlow's outlet, an upstream
      // complete indicates the remote has shutdown cleanly, a failure is
      // abnormal termination/connection refused.  Successful requests
      // will show up in `onPush`
      private val connectionInFlowHandler = new InHandler {
        // inner stream pushes we push downstream
        override def onPush(): Unit = {
          val response = connectionFlowSink.grab()
          val requestContext = inflightRequests.pop

          val (entity, whenCompleted) = HttpEntity.captureTermination(response.entity)
          import fm.executionContext
          push(responsesOut, ResponseContext(requestContext, Success(response withEntity entity)))
          push(eventsOut, SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx))))
        }

        override def onUpstreamFinish(): Unit = disconnect()

        override def onUpstreamFailure(ex: Throwable): Unit = disconnect(Some(ex))
      }

      // upstream pushes we create the inner stream if necessary or push if we're already connected
      override def onPush(): Unit = {
        def establishConnectionFlow() = {
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          isConnected = true

          Source.fromGraph(connectionFlowSource.source)
            .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

          connectionFlowSink.pull()
        }

        grab(in) match {
          case ConnectEagerlyCommand ⇒
            if (!isConnected) establishConnectionFlow()

            emit(eventsOut, ConnectedEagerly(slotIx))

          case DispatchCommand(rc: RequestContext) ⇒
            if (isConnected) {
              inflightRequests.add(rc)
              connectionFlowSource.push(rc.request)
            } else {
              firstRequest = rc
              establishConnectionFlow()
            }
        }
      }

      setHandler(in, this)

      setHandler(responsesOut, new OutHandler {
        override def onPull(): Unit = {
          // downstream pulls, if connected we pull inner
          if (isConnected) connectionFlowSink.pull()
          else if (!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(eventsOut, EagerTerminateOutput)
    }
  }
}
