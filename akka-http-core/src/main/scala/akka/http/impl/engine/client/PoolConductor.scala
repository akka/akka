/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import language.existentials
import scala.annotation.tailrec
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.scaladsl._
import akka.stream._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model.HttpMethod
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler

private object PoolConductor {
  import PoolFlow.RequestContext
  import PoolSlot.{ RawSlotEvent, SlotEvent }

  case class Ports(
    requestIn:   Inlet[RequestContext],
    slotEventIn: Inlet[RawSlotEvent],
    slotOuts:    immutable.Seq[Outlet[RequestContext]]) extends Shape {

    override val inlets = requestIn :: slotEventIn :: Nil
    override def outlets = slotOuts

    override def deepCopy(): Shape =
      Ports(
        requestIn.carbonCopy(),
        slotEventIn.carbonCopy(),
        slotOuts.map(_.carbonCopy()))

    override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape =
      Ports(
        inlets.head.asInstanceOf[Inlet[RequestContext]],
        inlets.last.asInstanceOf[Inlet[RawSlotEvent]],
        outlets.asInstanceOf[immutable.Seq[Outlet[RequestContext]]])
  }

  /*
    Stream Setup
    ============
                                                                                                  Request-
    Request-   +-----------+     +-----------+    Switch-    +-------------+     +-----------+    Context
    Context    |   retry   |     |   slot-   |    Command    |   doubler   |     |   route   +-------------->
    +--------->|   Merge   +---->| Selector  +-------------->| (MapConcat) +---->|  (Flexi   +-------------->
               |           |     |           |               |             |     |   Route)  +-------------->
               +----+------+     +-----+-----+               +-------------+     +-----------+       to slots
                    ^                  ^
                    |                  | SlotEvent
                    |             +----+----+
                    |             | flatten | mapAsync
                    |             +----+----+
                    |                  | RawSlotEvent
                    | Request-         |
                    | Context     +---------+
                    +-------------+  retry  |<-------- RawSlotEvent (from slotEventMerge)
                                  |  Split  |
                                  +---------+

  */
  def apply(slotCount: Int, pipeliningLimit: Int, log: LoggingAdapter): Graph[Ports, Any] =
    GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val retryMerge = b.add(MergePreferred[RequestContext](1, eagerComplete = true))
      val slotSelector = b.add(new SlotSelector(slotCount, pipeliningLimit, log))
      val route = b.add(new Route(slotCount))
      val retrySplit = b.add(Broadcast[RawSlotEvent](2))
      val flatten = Flow[RawSlotEvent].mapAsyncUnordered(slotCount) {
        case x: SlotEvent.Disconnected                ⇒ FastFuture.successful(x)
        case SlotEvent.RequestCompletedFuture(future) ⇒ future
        case x                                        ⇒ throw new IllegalStateException("Unexpected " + x)
      }

      retryMerge.out ~> slotSelector.in0
      slotSelector.out ~> route.in
      retrySplit.out(0).filter(!_.isInstanceOf[SlotEvent.RetryRequest]) ~> flatten ~> slotSelector.in1
      retrySplit.out(1).collect { case SlotEvent.RetryRequest(r) ⇒ r } ~> retryMerge.preferred

      Ports(retryMerge.in(0), retrySplit.in, route.outArray.toList)
    }

  private case class SwitchCommand(rc: RequestContext, slotIx: Int)

  // the SlotSelector keeps the state of all slots as instances of this ADT
  private sealed trait SlotState

  // the connection of the respective slot is not connected
  private case object Unconnected extends SlotState

  // the connection of the respective slot is connected with no requests currently in flight
  private case object Idle extends SlotState

  // the connection of the respective slot has a number of requests in flight and all of them
  // are idempotent which allows more requests to be pipelined onto the connection if required
  private final case class Loaded(openIdempotentRequests: Int) extends SlotState { require(openIdempotentRequests > 0) }

  // the connection of the respective slot has a number of requests in flight and the
  // last one of these is not idempotent which blocks the connection for more pipelined requests
  private case class Busy(openRequests: Int) extends SlotState { require(openRequests > 0) }
  private object Busy extends Busy(1)

  private class SlotSelector(slotCount: Int, pipeliningLimit: Int, log: LoggingAdapter)
    extends GraphStage[FanInShape2[RequestContext, SlotEvent, SwitchCommand]] {

    private val ctxIn = Inlet[RequestContext]("requestContext")
    private val slotIn = Inlet[SlotEvent]("slotEvents")
    private val out = Outlet[SwitchCommand]("switchCommand")

    override def initialAttributes = Attributes.name("SlotSelector")

    override val shape = new FanInShape2(ctxIn, slotIn, out)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      val slotStates = Array.fill[SlotState](slotCount)(Unconnected)
      var nextSlot = 0

      setHandler(ctxIn, new InHandler {
        override def onPush(): Unit = {
          val ctx = grab(ctxIn)
          val slot = nextSlot
          slotStates(slot) = slotStateAfterDispatch(slotStates(slot), ctx.request.method)
          nextSlot = bestSlot()
          emit(out, SwitchCommand(ctx, slot), tryPullCtx)
        }
      })

      setHandler(slotIn, new InHandler {
        override def onPush(): Unit = {
          grab(slotIn) match {
            case SlotEvent.RequestCompleted(slotIx) ⇒
              slotStates(slotIx) = slotStateAfterRequestCompleted(slotStates(slotIx))
            case SlotEvent.Disconnected(slotIx, failed) ⇒
              slotStates(slotIx) = slotStateAfterDisconnect(slotStates(slotIx), failed)
          }
          pull(slotIn)
          val wasBlocked = nextSlot == -1
          nextSlot = bestSlot()
          val nowUnblocked = nextSlot != -1
          if (wasBlocked && nowUnblocked) pull(ctxIn) // get next request context
        }
      })

      setHandler(out, eagerTerminateOutput)

      val tryPullCtx = () ⇒ if (nextSlot != -1 && !hasBeenPulled(ctxIn)) pull(ctxIn)

      override def preStart(): Unit = {
        pull(ctxIn)
        pull(slotIn)
      }

      def slotStateAfterDispatch(slotState: SlotState, method: HttpMethod): SlotState =
        slotState match {
          case Unconnected | Idle ⇒ if (method.isIdempotent) Loaded(1) else Busy(1)
          case Loaded(n)          ⇒ if (method.isIdempotent) Loaded(n + 1) else Busy(n + 1)
          case Busy(_)            ⇒ throw new IllegalStateException("Request scheduled onto busy connection?")
        }

      def slotStateAfterRequestCompleted(slotState: SlotState): SlotState =
        slotState match {
          case Loaded(1) ⇒ Idle
          case Loaded(n) ⇒ Loaded(n - 1)
          case Busy(1)   ⇒ Idle
          case Busy(n)   ⇒ Busy(n - 1)
          case _         ⇒ throw new IllegalStateException(s"RequestCompleted on $slotState connection?")
        }

      def slotStateAfterDisconnect(slotState: SlotState, failed: Int): SlotState =
        slotState match {
          case Idle if failed == 0      ⇒ Unconnected
          case Loaded(n) if n > failed  ⇒ Loaded(n - failed)
          case Loaded(n) if n == failed ⇒ Unconnected
          case Busy(n) if n > failed    ⇒ Busy(n - failed)
          case Busy(n) if n == failed   ⇒ Unconnected
          case _                        ⇒ throw new IllegalStateException(s"Disconnect(_, $failed) on $slotState connection?")
        }

      /**
       * Implements the following Connection Slot selection strategy
       *  - Select the first idle connection in the pool, if there is one.
       *  - If none is idle select the first unconnected connection, if there is one.
       *  - If all are loaded select the connection with the least open requests (< pipeliningLimit)
       *    that only has requests with idempotent methods scheduled to it, if there is one.
       *  - Otherwise return -1 (which applies back-pressure to the request source)
       *
       *  See http://tools.ietf.org/html/rfc7230#section-6.3.2 for more info on HTTP pipelining.
       */
      @tailrec def bestSlot(ix: Int = 0, bestIx: Int = -1, bestState: SlotState = Busy): Int =
        if (ix < slotStates.length) {
          val pl = pipeliningLimit
          slotStates(ix) → bestState match {
            case (Idle, _)                           ⇒ ix
            case (Unconnected, Loaded(_) | Busy)     ⇒ bestSlot(ix + 1, ix, Unconnected)
            case (x @ Loaded(a), Loaded(b)) if a < b ⇒ bestSlot(ix + 1, ix, x)
            case (x @ Loaded(a), Busy) if a < pl     ⇒ bestSlot(ix + 1, ix, x)
            case _                                   ⇒ bestSlot(ix + 1, bestIx, bestState)
          }
        } else bestIx
    }
  }

  private class Route(slotCount: Int) extends GraphStage[UniformFanOutShape[SwitchCommand, RequestContext]] {

    override def initialAttributes = Attributes.name("PoolConductor.Route")

    override val shape = new UniformFanOutShape[SwitchCommand, RequestContext](slotCount)

    override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
      shape.outArray foreach { setHandler(_, ignoreTerminateOutput) }

      val in = shape.in
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val cmd = grab(in)
          emit(shape.outArray(cmd.slotIx), cmd.rc, pullIn)
        }
      })
      val pullIn = () ⇒ pull(in)

      override def preStart(): Unit = pullIn()
    }
  }
}
