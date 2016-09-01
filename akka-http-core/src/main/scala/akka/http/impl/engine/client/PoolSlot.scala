/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.impl.engine.client.PoolSlot.SlotEvent.ConnectedEagerly
import akka.http.scaladsl.model.headers.Connection
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.GraphStageLogic.EagerTerminateOutput
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

import scala.concurrent.Future
import scala.language.existentials
import scala.util.{ Failure, Success }

private object PoolSlot {
  import PoolFlow.{ RequestContext, ResponseContext }

  sealed trait ProcessorOut
  final case class ResponseDelivery(response: ResponseContext) extends ProcessorOut
  sealed trait RawSlotEvent extends ProcessorOut
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

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler { self ⇒
      private val inflightRequests = new java.util.ArrayDeque[RequestContext]()

      private var connectionFlowSource: SubSourceOutlet[HttpRequest] = _
      private var connectionFlowSink: SubSinkInlet[HttpResponse] = _

      private var isConnected = false

      private def connectionOutFlowHandler = new OutHandler {
        override def onPull(): Unit = ()

        override def onDownstreamFinish(): Unit = {
          if (inflightRequests.isEmpty && isConnected) {
            push(out1, SlotEvent.Disconnected(slotIx, 0))
          }

          connectionFlowSource.complete()
        }
      }

      private def connectionInFlowHandler = new InHandler {
        override def onPush(): Unit = {
          val response: HttpResponse = connectionFlowSink.grab()

          val requestContext = inflightRequests.pop
          val (entity, whenCompleted) = HttpEntity.captureTermination(response.entity)
          val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response withEntity entity)))
          import fm.executionContext
          val requestCompleted = SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx)))
          push(out0, delivery.response)
          push(out1, requestCompleted)

          // the connectionFlow uses One2OneBidiFlow so its strictly one in, one out and
          // since any request can close the connection we can't stack them
          if (response.header[Connection].forall(!_.hasClose)) {
            pull(in)
          }

          connectionFlowSink.pull()
        }

        private def disconnect(): Unit = {
          isConnected = false
          setHandler(in, self)
          if (!hasBeenPulled(in)) pull(in)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          if (isConnected) {
            val it = inflightRequests.descendingIterator()
            while (it.hasNext) {
              val rc = it.next()
              if (rc.retriesLeft == 0) { println(s"failing $rc"); emit(out0, ResponseContext(rc, Failure(ex))) }
              else emit(out1, SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1)))
            }
            emit(out1, SlotEvent.Disconnected(slotIx, inflightRequests.size))
            inflightRequests.clear()

            connectionFlowSource.complete()

            disconnect()
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (isConnected) {
            push(out1, SlotEvent.Disconnected(slotIx, 0))

            disconnect()
          }
        }
      }

      private lazy val connected = new InHandler {
        override def onPush(): Unit = grab(in) match {
          case DispatchCommand(rc: RequestContext) ⇒
            inflightRequests.add(rc)
            connectionFlowSource.push(rc.request)
          case x ⇒
            log.error("invalid command {} when connected", x)
        }
      }

      // unconnected
      override def onPush(): Unit = {
        def createSubSourceOutlets() = {
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          setHandler(in, connected)
          isConnected = true
        }

        grab(in) match {
          case ConnectEagerlyCommand ⇒
            createSubSourceOutlets()

            Source.fromGraph(connectionFlowSource.source)
              .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

            connectionFlowSink.pull()
            pull(in)

            push(out1, ConnectedEagerly(slotIx))

          case DispatchCommand(rc: RequestContext) ⇒
            createSubSourceOutlets()

            inflightRequests.add(rc)

            Source.single(rc.request).concat(Source.fromGraph(connectionFlowSource.source))
              .via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

            connectionFlowSink.pull()
        }
      }

      setHandler(in, this)

      setHandler(out0, new OutHandler() {
        override def onPull(): Unit = if (!isConnected && !hasBeenPulled(in)) pull(in)
      })
      setHandler(out1, EagerTerminateOutput)
    }

  }

  final class UnexpectedDisconnectException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
    def this(msg: String) = this(msg, null)
  }
}
