/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.impl.engine.client.PoolSlot.SlotEvent.ConnectedEagerly
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.http.scaladsl.util.FastFuture
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

    val slotCommandIn: Inlet[SlotCommand] = Inlet("SlotProcessor.slotCommandIn")
    val responseOut: Outlet[ResponseContext] = Outlet("SlotProcessor.responseOut")
    val eventOut: Outlet[RawSlotEvent] = Outlet("SlotProcessor.eventOut")

    override def shape: FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent] = new FanOutShape2(slotCommandIn, responseOut, eventOut)

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

          emitMultiple(responseOut, failures)
          emitMultiple(eventOut, SlotEvent.Disconnected(slotIx, retries.size + failures.size) :: retries, () ⇒ if (failures.isEmpty && !hasBeenPulled(slotCommandIn)) pull(slotCommandIn))
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
          } else pull(slotCommandIn)
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
          push(responseOut, ResponseContext(requestContext, Success(response withEntity entity)))

          // Recover to avoid pool shutdown in case of slot failing due to failure of request completion
          // and this failure being propagated with the future to the pool.
          // This will emit both Disconnect (with zero failures as we have just popped the inflightRequest)
          // Due to PoolSlot.onUpstreamFailure being called as well as RequestCompleted
          // following two scenarios are possible as there is an inherent race (option 1. is what usually happens):
          // 1. Fail after the response headers (onUpstreamFailure and then Future failed)
          //    SlotEvent.Disconnected(slot, 0); transitions from Loaded(1) => Loaded(1)
          //    SlotEvent.RequestCompleted(slot); transitions from Loaded(1) => Idle
          // 2. Fail after the response headers (Future failed and then onUpstreamFailure);
          //    SlotEvent.RequestCompleted(slot); transitions from Loaded(1) => Idle
          //    SlotEvent.Disconnected(slot, 0); transitions from Idle => Unconnected
          // the result is that the final state of the slot is usually Idle which results in this slot interpreted as hot
          // and being preferred by the scheduler instead of more correct Unconnected
          // FIXME: enhance the event & processing in the connection pool state machine to allow proper transition, see
          // https://github.com/akka/akka-http/issues/490
          val completed = whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx))
            .recoverWith { case _ ⇒ FastFuture.successful(SlotEvent.RequestCompleted(slotIx)) }
          push(eventOut, SlotEvent.RequestCompletedFuture(completed))
        }

        override def onUpstreamFinish(): Unit = disconnect()

        override def onUpstreamFailure(ex: Throwable): Unit = disconnect(Some(ex))
      }

      // upstream pushes we create the inner stream if necessary or push if we're already connected
      override def onPush(): Unit = {
        def establishConnectionFlow() = {
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("SlotProcessor.RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("SlotProcessor.ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          isConnected = true

          Source.fromGraph(connectionFlowSource.source)
            .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

          connectionFlowSink.pull()
        }

        grab(slotCommandIn) match {
          case ConnectEagerlyCommand ⇒
            if (!isConnected) establishConnectionFlow()

            emit(eventOut, ConnectedEagerly(slotIx))

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

      setHandler(slotCommandIn, this)

      setHandler(responseOut, new OutHandler {
        override def onPull(): Unit = {
          // downstream pulls, if connected we pull inner
          if (isConnected) connectionFlowSink.pull()
          else if (!hasBeenPulled(slotCommandIn)) pull(slotCommandIn)
        }
      })

      setHandler(eventOut, EagerTerminateOutput)
    }
  }
}
