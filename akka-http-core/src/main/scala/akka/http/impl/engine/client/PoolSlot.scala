/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.stream.impl.{ ActorMaterializerImpl, ConstantFun }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Graph, Materializer }

import scala.collection.immutable
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

  /*
    Stream Setup
    ============

    Request-   +-----------+              +-------------+              +------------+
    Context    | Slot-     |  List[       |   flatten   |  Processor-  | SlotEvent- |  Response-
    +--------->| Processor +------------->| (MapConcat) +------------->| Split      +------------->
               |           |  Processor-  |             |  Out         |            |  Context
               +-----------+  Out]        +-------------+              +-----+------+
                                                                             | RawSlotEvent
                                                                             | (to Conductor
                                                                             |  via slotEventMerge)
                                                                             v
   */
  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any])(implicit m: Materializer): Graph[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent], Any] =
    GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val log = ActorMaterializerHelper.downcast(m).logger
      val slotProcessor: FlowShape[SlotCommand, ProcessorOut] = b.add(new SlotProcessor(slotIx, connectionFlow, log))
      val partition = b.add(Partition[ProcessorOut](2, {
        case ResponseDelivery(r) ⇒ 0
        case r: RawSlotEvent     ⇒ 1
      }))
      slotProcessor.out ~> partition.in

      val deliveries = partition.out(0).map(_.asInstanceOf[ResponseDelivery].response)
      val events = partition.out(1).map(_.asInstanceOf[RawSlotEvent])
      new FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent](slotProcessor.in, deliveries.outlet, events.outlet)
    }

  /**
   * An actor managing a series of materializations of the given `connectionFlow`.
   *
   * To the outside it provides a stable flow stage, consuming `SlotCommand` instances on its internal connection flows.
   *
   * The given `connectionFlow` is materialized into a running flow whenever required.
   * Completion and errors from the connection are not surfaced to the outside (unless we are
   * shutting down completely).
   */
  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any], log: LoggingAdapter)(implicit fm: Materializer)
    extends GraphStage[FlowShape[SlotCommand, ProcessorOut]] {

    val in: Inlet[SlotCommand] = Inlet("SlotProcessor.in")
    val out: Outlet[ProcessorOut] = Outlet("SlotProcessor.out")

    override def shape: FlowShape[SlotCommand, ProcessorOut] = new FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) 
      with InHandler with OutHandler { self ⇒
      private final val inflightRequests = new java.util.ArrayDeque[RequestContext]()

      private[this] var connectionFlowSource: SubSourceOutlet[HttpRequest] = _
      private[this] var connectionFlowSink: SubSinkInlet[HttpResponse] = _

      private[this] var firstRequest: RequestContext = _

      // request first request/command
      override def preStart(): Unit = pull(in)

      private lazy val connectionOutFlowHandler = new OutHandler {
        // connectionFlowSource is ready for an element, we can send a HttpRequest to the subflow
        override def onPull(): Unit = {
          log.debug("BERN-{}: connectionFlow: onPull, first {} inflight {}", slotIx, firstRequest, inflightRequests)
          // give the connectionFlow a HttpRequest
          if (firstRequest ne null) {
            inflightRequests.add(firstRequest)
            connectionFlowSource.push(firstRequest.request)

            firstRequest = null
          } else if (isAvailable(in)) grab(in) match {
            case DispatchCommand(rc) ⇒
              inflightRequests.add(rc)
              connectionFlowSource.push(rc.request)
          }
          if (!hasBeenPulled(in)) pull(in)
        }

        // connectionFlowSource has been closed (IgnoreTerminateOutput)
        override def onDownstreamFinish(): Unit = {
          log.debug("BERN-{}: onDownstreamFinish first {} inflight {}!!", slotIx, firstRequest, inflightRequests)

          connectionFlowSource.complete()

          if (firstRequest == null && inflightRequests.isEmpty) {
            push(out, SlotEvent.Disconnected(slotIx, 0))

            connectionFlowSource.complete()
            setHandler(in, self)

          }
        }
      }

      private lazy val connectionInFlowHandler = new InHandler {

        // a new element is available on connectionFlowSink Inlet - that is a HttpResponse is being returned
        override def onPush(): Unit = {
          log.debug("BERN-{}: connectionFlow: onPush", slotIx)
          // consume a HttpResponse from the connectonFlow

          val response: HttpResponse = connectionFlowSink.grab()

          log.debug("BERN-{}: connectionFlow: onPush {} {}", slotIx, response)
          val requestContext = inflightRequests.pop()

          val (entity, whenCompleted) = HttpEntity.captureTermination(response.entity)
          val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response withEntity entity)))
          import fm.executionContext
          val requestCompleted = SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx)))
          emitMultiple(out, delivery :: requestCompleted :: Nil)

          connectionFlowSink.pull()
        }

        // this would happen if we closed the source (so won't happen)
        override def onUpstreamFinish(): Unit = ()

        // a Failure[HttpResponse] is coming back instead
        override def onUpstreamFailure(ex: Throwable): Unit = {
          log.error(ex, "BERN-{}: onUpstreamFailure first {} inflight {}", slotIx, firstRequest, inflightRequests)
          val results: List[ProcessorOut] =
            if (firstRequest ne null) {
              ResponseDelivery(ResponseContext(firstRequest, Failure(new UnexpectedDisconnectException("Unexpected (early) disconnect", ex)))) :: Nil
            } else {
              // TODO replace ad-hoc retries with Retry stage?
              var rs: List[ProcessorOut] = Nil
              val it = inflightRequests.descendingIterator()
              while (it.hasNext) {
                val rc = it.next()
                val modified =
                  if (rc.retriesLeft == 0) ResponseDelivery(ResponseContext(rc, Failure(ex)))
                  else SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1))
                rs = modified :: rs
              }
              rs
            }
          firstRequest = null
          inflightRequests.clear()
          emitMultiple(out, SlotEvent.Disconnected(slotIx, results.size) :: results)

          connectionFlowSource.complete()
          setHandler(in, self)
        }
      }

      private lazy val connected = new InHandler {
        override def onPush(): Unit = {
          log.debug("BERN-{}: PoolSlot: onPush when connected", slotIx)
          if (connectionFlowSource.isAvailable) {
            grab(in) match {
              case DispatchCommand(rc: RequestContext) ⇒
                inflightRequests.add(rc)
                connectionFlowSource.push(rc.request)
              case x ⇒
                log.error("invalid command {}", x)
            }
            pull(in)
          } else if (!connectionFlowSink.hasBeenPulled) {
            log.warning("connectionFlowSink.isAvailable = " + connectionFlowSink.isAvailable)
            log.warning("connectionFlowSink.hasBeenPulled = " + connectionFlowSink.hasBeenPulled)
            log.warning("connectionFlowSink.isClosed = " + connectionFlowSink.isClosed)
            if (!connectionFlowSink.isClosed) connectionFlowSink.pull() // FIXME, weird?
          }
        }
      }

      // unconnected
      override def onPush(): Unit = grab(in) match {
        case ConnectEagerlyCommand ⇒
          log.debug("BERN-{}: PoolSlot: onPush ConnectEagerlyCommand when unconnected", slotIx)
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          setHandler(in, connected)

          Source.fromGraph(connectionFlowSource.source).via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

          connectionFlowSink.pull()

        case DispatchCommand(rc: RequestContext) ⇒
          log.debug("BERN-{}: PoolSlot: onPush({}) when unconnected", slotIx, rc)
          connectionFlowSource = new SubSourceOutlet[HttpRequest]("RequestSource")
          connectionFlowSource.setHandler(connectionOutFlowHandler)

          connectionFlowSink = new SubSinkInlet[HttpResponse]("ResponseSink")
          connectionFlowSink.setHandler(connectionInFlowHandler)

          firstRequest = rc

          setHandler(in, connected)

          Source.fromGraph(connectionFlowSource.source).via(connectionFlow).runWith(Sink.fromGraph(connectionFlowSink.sink))(subFusingMaterializer)

          connectionFlowSink.pull()
      }

      // OutHandler, downstream has requested an element
      override def onPull(): Unit = {
        log.debug("BERN-{}: PoolSlot: onPull", slotIx)
      }

      setHandlers(in, out, this)
    }
  }

  final class UnexpectedDisconnectException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
    def this(msg: String) = this(msg, null)
  }
}
