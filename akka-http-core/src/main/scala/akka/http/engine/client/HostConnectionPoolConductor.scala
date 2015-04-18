/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import language.existentials
import scala.annotation.tailrec
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.scaladsl._
import akka.stream._
import akka.http.model.HttpMethod
import akka.http.util._

private object HostConnectionPoolConductor {
  import HostConnectionPoolGateway.RequestContext
  import HostConnectionPoolSlot.{ SlotEvent, SimpleSlotEvent }

  case class Ports(
    requestIn: Inlet[RequestContext],
    slotEventIn: Inlet[SlotEvent],
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
        inlets.last.asInstanceOf[Inlet[SlotEvent]],
        outlets.asInstanceOf[immutable.Seq[Outlet[RequestContext]]])
  }

  /*
    Stream Setup
    ============
                                                                                                  Request- 
    Request-   +-----------+     +-----------+    Switch-    +-------------+     +-----------+    Context
    Context    |   merge1  |     |   merge2  |    Command    |   doubler   |     |   route   +-------------->
    +--------->|  (Merge-  +---->|  (Flexi   +-------------->| (MapConcat) +---->|  (Flexi   +-------------->
               | Preferred)|     |   Merge)  |               |             |     |   Route)  +-------------->
               +----+------+     +-----+-----+               +-------------+     +-----------+       to slots     
                    ^                  ^                                                               
                    |                  | SimpleSlotEvent                                              
                    | Request-         |                                                               
                    | Context     +---------+
                    +-------------+  retry  |<-------- Slot Event (from slotEventMerge)
                                  |  Split  |
                                  +---------+

  */
  def apply(slotCount: Int, maxRetries: Int, pipeliningLimit: Int, log: LoggingAdapter): Graph[Ports, Any] =
    FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._

      val merge1 = b.add(new Merge1)
      val merge2 = b.add(new Merge2(slotCount, maxRetries, pipeliningLimit, log))
      val doubler = Flow[SwitchCommand].mapConcat(x ⇒ x :: x :: Nil)
      val route = b.add(new Route(slotCount))
      val retrySplit = b.add(new RetrySplit())

      merge1.out ~> merge2.in0
      merge2.out ~> doubler ~> route.in
      retrySplit.out0 ~> merge2.in1
      retrySplit.out1 ~> merge1.in1

      Ports(merge1.in0, retrySplit.in, route.outlets.asInstanceOf[immutable.Seq[Outlet[RequestContext]]])
    }

  // almost the same as a MergePreferred,
  // but as MergePreferred doesn't appear to propagate completion on the secondary input we can use it here
  private class Merge1 extends FlexiMerge[RequestContext, FanInShape2[RequestContext, RequestContext, RequestContext]](
    new FanInShape2("Merge1"), OperationAttributes.name("Merge1")) {
    import FlexiMerge._

    def createMergeLogic(shape: FanInShape2[RequestContext, RequestContext, RequestContext]) =
      new MergeLogic[RequestContext] {
        val freshInlet = shape.in0
        val retryInlet = shape.in1

        override def initialState = State(ReadAny(retryInlet, freshInlet)) {
          case (ctx, `retryInlet`, rc: RequestContext) ⇒ { ctx.emit(rc); SameState }
          case (ctx, `freshInlet`, rc: RequestContext) ⇒ { ctx.emit(rc); SameState }
        }

        override def initialCompletionHandling = eagerClose
      }
  }

  private case class SwitchCommand(rc: RequestContext, slotIx: Int)

  private sealed trait SlotState
  private case object Unconnected extends SlotState
  private case object Idle extends SlotState
  private final case class Loaded(openIdempotentRequests: Int) extends SlotState { require(openIdempotentRequests > 0) }
  private case class Busy(openRequests: Int) extends SlotState { require(openRequests > 0) }
  private object Busy extends Busy(1)

  private class Merge2(slotCount: Int, maxRetries: Int, pipeliningLimit: Int, log: LoggingAdapter)
    extends FlexiMerge[SwitchCommand, FanInShape2[RequestContext, SimpleSlotEvent, SwitchCommand]](
      new FanInShape2("HostConnectionPoolConductor.merge2"),
      OperationAttributes.name("HostConnectionPoolConductor.merge2")) {
    import FlexiMerge._

    def createMergeLogic(s: FanInShape2[RequestContext, SimpleSlotEvent, SwitchCommand]): MergeLogic[SwitchCommand] =
      new MergeLogic[SwitchCommand] {
        val slotStates = Array.fill[SlotState](slotCount)(Unconnected)
        def initialState = nextState(0)
        override def initialCompletionHandling = eagerClose

        def nextState(currentSlot: Int): State[_] = {
          val read: ReadCondition[_] = currentSlot match {
            case -1 ⇒ Read(s.in1) // if we have no slot available we are not reading from upstream (only SlotEvents)
            case _  ⇒ ReadAny(s) // otherwise we read SlotEvent *as well as* from upstream
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
            case (Unconnected | Idle) if method.isIdempotent ⇒ Loaded(1)
            case Unconnected | Idle                          ⇒ Busy(1)
            case Loaded(n) if method.isIdempotent            ⇒ Loaded(n + 1)
            case Loaded(n)                                   ⇒ Busy(n + 1)
            case Busy(_)                                     ⇒ throw new IllegalStateException("Request scheduled onto busy connection?")
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
            def isBetterThanBest(arg: Loaded) = bestState match {
              case Unconnected ⇒ false
              case Loaded(x) if x <= arg.openIdempotentRequests ⇒ false
              case _ ⇒ arg.openIdempotentRequests < pipeliningLimit
            }
            slotStates(ix) match {
              case Idle                             ⇒ ix
              case Unconnected                      ⇒ bestSlot(ix + 1, ix, Unconnected)
              case x: Loaded if isBetterThanBest(x) ⇒ bestSlot(ix + 1, ix, x)
              case _                                ⇒ bestSlot(ix + 1, bestIx, bestState)
            }
          } else bestIx
      }
  }

  private class Route(slotCount: Int) extends FlexiRoute[SwitchCommand, UniformFanOutShape[SwitchCommand, RequestContext]](
    new UniformFanOutShape(slotCount, "HostConnectionPoolConductor.Route"),
    OperationAttributes.name("HostConnectionPoolConductor.Route")) {
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
  private class RetrySplit extends FlexiRoute[SlotEvent, FanOutShape2[SlotEvent, SimpleSlotEvent, RequestContext]](
    new FanOutShape2("HostConnectionPoolConductor.RetrySplit"),
    OperationAttributes.name("HostConnectionPoolConductor.RetrySplit")) {
    import FlexiRoute._

    def createRouteLogic(s: FanOutShape2[SlotEvent, SimpleSlotEvent, RequestContext]): RouteLogic[SlotEvent] =
      new RouteLogic[SlotEvent] {
        def initialState: State[_] = State(DemandFromAll(s)) { (ctx, _, ev) ⇒
          ev match {
            case x: SimpleSlotEvent         ⇒ ctx.emit(s.out0)(x)
            case SlotEvent.RetryRequest(rc) ⇒ ctx.emit(s.out1)(rc)
          }
          SameState
        }
      }
  }
}
