/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
import akka.http.impl.util._

private object PoolConductor {
  import PoolFlow.RequestContext
  import PoolSlot.{ RawSlotEvent, SlotEvent }

  case class Ports(
    requestIn: Inlet[RequestContext],
    slotEventIn: Inlet[RawSlotEvent],
    slotOuts: immutable.Seq[Outlet[RequestContext]]) extends Shape {

    override val inlets = requestIn :: slotEventIn :: Nil
    override def outlets = slotOuts

    override def deepCopy(): Shape =
      Ports(
        new Inlet(requestIn.toString),
        new Inlet(slotEventIn.toString),
        slotOuts.map(o ⇒ new Outlet(o.toString)))

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
    Request-   +-----------+     +-----------+    Switch-    +-------------+     +-----------+    Context
    Context    |   retry   |     |   slot-   |    Command    |   doubler   |     |   route   +-------------->
    +--------->|   Merge   +---->| Selector  +-------------->| (MapConcat) +---->|  (Flexi   +-------------->
               |           |     |           |               |             |     |   Route)  +-------------->
               +----+------+     +-----+-----+               +-------------+     +-----------+       to slots     
                    ^                  ^ 
                    |                  | SlotEvent
                    |             +----+----+
                    |             | flatten | mapAsync
                    |             +----+----+
                    |                  | RawSlotEvent
                    | Request-         |                                                               
                    | Context     +---------+
                    +-------------+  retry  |<-------- RawSlotEvent (from slotEventMerge)
                                  |  Split  |
                                  +---------+

  */
  def apply(slotCount: Int, maxRetries: Int, pipeliningLimit: Int, log: LoggingAdapter): Graph[Ports, Any] =
    FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._

      // actually we want a `MergePreferred` here (and prefer the `retryInlet`),
      // but as MergePreferred doesn't propagate completion on the secondary input we can't use it here
      val retryMerge = b.add(new StreamUtils.EagerCloseMerge2[RequestContext]("PoolConductor.retryMerge"))
      val slotSelector = b.add(new SlotSelector(slotCount, maxRetries, pipeliningLimit, log))
      val doubler = Flow[SwitchCommand].mapConcat(x ⇒ x :: x :: Nil) // work-around for https://github.com/akka/akka/issues/17004
      val route = b.add(new Route(slotCount))
      val retrySplit = b.add(new RetrySplit())
      val flatten = Flow[RawSlotEvent].mapAsyncUnordered(slotCount) {
        case x: SlotEvent.Disconnected                ⇒ FastFuture.successful(x)
        case SlotEvent.RequestCompletedFuture(future) ⇒ future
        case x                                        ⇒ throw new IllegalStateException("Unexpected " + x)
      }

      retryMerge.out ~> slotSelector.in0
      slotSelector.out ~> doubler ~> route.in
      retrySplit.out0 ~> flatten ~> slotSelector.in1
      retrySplit.out1 ~> retryMerge.in1

      Ports(retryMerge.in0, retrySplit.in, route.outlets.asInstanceOf[immutable.Seq[Outlet[RequestContext]]])
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

  private class SlotSelector(slotCount: Int, maxRetries: Int, pipeliningLimit: Int, log: LoggingAdapter)
    extends FlexiMerge[SwitchCommand, FanInShape2[RequestContext, SlotEvent, SwitchCommand]](
      new FanInShape2("PoolConductor.SlotSelector"), OperationAttributes.name("PoolConductor.SlotSelector")) {
    import FlexiMerge._

    def createMergeLogic(s: FanInShape2[RequestContext, SlotEvent, SwitchCommand]): MergeLogic[SwitchCommand] =
      new MergeLogic[SwitchCommand] {
        val slotStates = Array.fill[SlotState](slotCount)(Unconnected)
        def initialState = nextState(0)
        override def initialCompletionHandling = eagerClose

        def nextState(currentSlot: Int): State[_] = {
          val read: ReadCondition[_] = currentSlot match {
            case -1 ⇒ Read(s.in1) // if we have no slot available we are not reading from upstream (only SlotEvents)
            case _  ⇒ ReadAny(s) // otherwise we read SlotEvents *as well as* from upstream
          }
          State(read) { (ctx, inlet, element) ⇒
            element match {
              case rc: RequestContext ⇒
                ctx.emit(SwitchCommand(rc, currentSlot))
                slotStates(currentSlot) = slotStateAfterDispatch(slotStates(currentSlot), rc.request.method)
              case SlotEvent.RequestCompleted(slotIx) ⇒
                slotStates(slotIx) = slotStateAfterRequestCompleted(slotStates(slotIx))
              case SlotEvent.Disconnected(slotIx, failed) ⇒
                slotStates(slotIx) = slotStateAfterDisconnect(slotStates(slotIx), failed)
            }
            nextState(bestSlot())
          }
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
            slotStates(ix) -> bestState match {
              case (Idle, _)                           ⇒ ix
              case (Unconnected, Loaded(_) | Busy)     ⇒ bestSlot(ix + 1, ix, Unconnected)
              case (x @ Loaded(a), Loaded(b)) if a < b ⇒ bestSlot(ix + 1, ix, x)
              case (x @ Loaded(a), Busy) if a < pl     ⇒ bestSlot(ix + 1, ix, x)
              case _                                   ⇒ bestSlot(ix + 1, bestIx, bestState)
            }
          } else bestIx
      }
  }

  private class Route(slotCount: Int) extends FlexiRoute[SwitchCommand, UniformFanOutShape[SwitchCommand, RequestContext]](
    new UniformFanOutShape(slotCount, "PoolConductor.Route"), OperationAttributes.name("PoolConductor.Route")) {
    import FlexiRoute._

    def createRouteLogic(s: UniformFanOutShape[SwitchCommand, RequestContext]): RouteLogic[SwitchCommand] =
      new RouteLogic[SwitchCommand] {
        val initialState: State[_] = State(DemandFromAny(s)) {
          case (_, _, SwitchCommand(req, slotIx)) ⇒
            State(DemandFrom(s.out(slotIx))) { (ctx, out, _) ⇒
              ctx.emit(out)(req)
              initialState
            }
        }
        override def initialCompletionHandling = CompletionHandling(
          onUpstreamFinish = ctx ⇒ { ctx.finish(); SameState },
          onUpstreamFailure = (ctx, cause) ⇒ { ctx.fail(cause); SameState },
          onDownstreamFinish = (ctx, _) ⇒ SameState)
      }
  }

  // FIXME: remove when #17038 is cleared
  private class RetrySplit extends FlexiRoute[RawSlotEvent, FanOutShape2[RawSlotEvent, RawSlotEvent, RequestContext]](
    new FanOutShape2("PoolConductor.RetrySplit"), OperationAttributes.name("PoolConductor.RetrySplit")) {
    import FlexiRoute._

    def createRouteLogic(s: FanOutShape2[RawSlotEvent, RawSlotEvent, RequestContext]): RouteLogic[RawSlotEvent] =
      new RouteLogic[RawSlotEvent] {
        def initialState: State[_] = State(DemandFromAll(s)) { (ctx, _, ev) ⇒
          ev match {
            case SlotEvent.RetryRequest(rc) ⇒ ctx.emit(s.out1)(rc)
            case x                          ⇒ ctx.emit(s.out0)(x)
          }
          SameState
        }
      }
  }
}
