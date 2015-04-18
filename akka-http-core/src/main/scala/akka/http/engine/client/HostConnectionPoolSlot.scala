/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import language.existentials
import java.net.InetSocketAddress
import scala.util.{ Failure, Success }
import scala.collection.immutable
import akka.actor._
import akka.http.model.{ HttpResponse, HttpRequest }
import akka.http.util._
import akka.stream.impl.{ SubscribePending, ExposedPublisher, ActorProcessor }
import akka.stream.actor._
import akka.stream.scaladsl._
import akka.stream._

private object HostConnectionPoolSlot {
  import HostConnectionPoolGateway.{ RequestContext, ResponseContext }

  sealed trait ProcessorOut
  final case class ResponseDelivery(response: ResponseContext) extends ProcessorOut
  sealed trait SlotEvent extends ProcessorOut
  sealed trait SimpleSlotEvent extends SlotEvent
  object SlotEvent {
    final case class RequestCompleted(slotIx: Int) extends SimpleSlotEvent
    final case class Disconnected(slotIx: Int, failedRequests: Int) extends SimpleSlotEvent
    final case class RetryRequest(rc: RequestContext) extends SlotEvent
  }

  case class Ports(
    requestContextIn: Inlet[RequestContext],
    responseOut: Outlet[ResponseContext],
    slotEventOut: Outlet[SlotEvent]) extends Shape {

    override val inlets = requestContextIn :: Nil
    override def outlets = responseOut :: slotEventOut :: Nil

    override def deepCopy(): Shape = Ports(
      new Inlet(requestContextIn.toString),
      new Outlet(responseOut.toString),
      new Outlet(slotEventOut.toString))

    override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape =
      Ports(
        inlets.head.asInstanceOf[Inlet[RequestContext]],
        outlets.head.asInstanceOf[Outlet[ResponseContext]],
        outlets.last.asInstanceOf[Outlet[SlotEvent]])
  }

  private val slotProcessorActorName = new SeqActorName("SlotProcessor")

  /*
    Stream Setup
    ============

    Request-   +-----------+                       +-------------+                +------------+ 
    Context    | Slot-     |  List[ProcessorOut]   |   flatten   |  ProcessorOut  | SlotEvent- |    ReponseContext
    +--------->| Processor +---------------------->| (MapConcat) +--------------->| Split      +------------------>
               |           |                       |             |                |            |                                    
               +-----------+                       +-------------+                +-----+------+                                    
                                                                                        | SlotEvent                                                    
                                                                                        | (to Conductor
                                                                                        |  via slotEventMerge)
                                                                                        v 
   */
  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
            remoteAddress: InetSocketAddress, // TODO: remove after #16168 is cleared
            settings: HostConnectionPoolSettings)(implicit system: ActorSystem, fm: FlowMaterializer): Graph[Ports, Any] =
    FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._

      val slotProcessor = b.add {
        Flow[RequestContext] andThenMat { () ⇒
          val actor = system.actorOf(Props(new SlotProcessor(slotIx, connectionFlow, settings)), slotProcessorActorName.next())
          (ActorProcessor[RequestContext, List[ProcessorOut]](actor), ())
        }
      }
      val flatten = Flow[List[ProcessorOut]].mapConcat(identityFunc)
      val split = b.add(new SlotEventSplit)

      slotProcessor ~> flatten ~> split.in
      Ports(slotProcessor.inlet, split.out0, split.out1)
    }

  import ActorSubscriberMessage._
  import ActorPublisherMessage._

  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
                              settings: HostConnectionPoolSettings)(implicit fm: FlowMaterializer)
    extends ActorSubscriber with ActorPublisher[List[ProcessorOut]] with LogMessages {

    var exposedPublisher: akka.stream.impl.ActorPublisher[Any] = _
    var inflightRequests = immutable.Queue.empty[RequestContext]
    val runnableFlow = Source.actorPublisher[HttpRequest](Props(new FlowInportActor(self)))
      .via(connectionFlow)
      .toMat(Sink.actorSubscriber[HttpResponse](Props(new FlowOutportActor(self))))(_ -> _)

    def requestStrategy = ZeroRequestStrategy
    def receive = waitingExposedPublisher

    def waitingExposedPublisher: Receive = {
      case ExposedPublisher(publisher) ⇒
        exposedPublisher = publisher
        context.become(waitingForSubscribePending)
      case other ⇒ throw new IllegalStateException(s"The first message must be `ExposedPublisher` but was [$other]")
    }

    def waitingForSubscribePending: Receive = {
      case SubscribePending ⇒
        exposedPublisher.takePendingSubscribers() foreach (s ⇒ self ! ActorPublisher.Internal.Subscribe(s))
        context.become(unconnected)
    }

    val unconnected: Receive = logMessages("unconnected") {
      case OnNext(rc: RequestContext) ⇒
        assert(totalDemand > 0)
        val (connInport, connOutport) = runnableFlow.run()
        connOutport ! Request(totalDemand)
        context.become(waitingForDemandFromConnection(connInport, connOutport, rc))

      case Request(_) ⇒ if (remainingRequested == 0) request(1) // ask for first request if necessary

      case Cancel     ⇒ { cancel(); shutdown() }
      case OnComplete ⇒ onComplete()
      case OnError(e) ⇒ onError(e)
    }

    def waitingForDemandFromConnection(connInport: ActorRef, connOutport: ActorRef,
                                       firstRequest: RequestContext): Receive = logMessages("waitingForDemandFromConnection") {
      case ev @ (Request(_) | Cancel)     ⇒ connOutport ! ev
      case ev @ (OnComplete | OnError(_)) ⇒ connInport ! ev
      case OnNext(x)                      ⇒ throw new IllegalStateException("Unrequested RequestContext: " + x)

      case FromConnection(Request(_)) ⇒
        inflightRequests = inflightRequests.enqueue(firstRequest)
        request(totalDemand - remainingRequested)
        connInport ! OnNext(firstRequest.request)
        context.become(running(connInport, connOutport))

      case FromConnection(Cancel)     ⇒ if (!isActive) { cancel(); shutdown() } // else ignore and wait for accompanying OnComplete or OnError
      case FromConnection(OnComplete) ⇒ handleDisconnect(None)
      case FromConnection(OnError(e)) ⇒ handleDisconnect(Some(e))
      case FromConnection(OnNext(x))  ⇒ throw new IllegalStateException("Unexpected HttpResponse: " + x)
    }

    def running(connInport: ActorRef, connOutport: ActorRef): Receive = logMessages("running") {
      case ev @ (Request(_) | Cancel)     ⇒ connOutport ! ev
      case ev @ (OnComplete | OnError(_)) ⇒ connInport ! ev
      case OnNext(rc: RequestContext) ⇒
        inflightRequests = inflightRequests.enqueue(rc)
        connInport ! OnNext(rc.request)

      case FromConnection(Request(n)) ⇒ request(n)
      case FromConnection(Cancel)     ⇒ if (!isActive) { cancel(); shutdown() } // else ignore and wait for accompanying OnComplete or OnError

      case FromConnection(OnNext(response: HttpResponse)) ⇒
        val requestContext = inflightRequests.head
        inflightRequests = inflightRequests.tail
        val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response)))
        val requestCompleted = SlotEvent.RequestCompleted(slotIx)
        onNext(delivery :: requestCompleted :: Nil)

      case FromConnection(OnComplete) ⇒ handleDisconnect(None)
      case FromConnection(OnError(e)) ⇒ handleDisconnect(Some(e))
    }

    def handleDisconnect(error: Option[Throwable]): Unit = {
      log.debug("Slot {} disconnected after {}", slotIx, error getOrElse "regular connection close")
      val results: List[ProcessorOut] = inflightRequests.map { rc ⇒
        if (rc.retriesLeft == 0) {
          val reason = error.fold[Throwable](new RuntimeException("Unexpected disconnect"))(identityFunc)
          ResponseDelivery(ResponseContext(rc, Failure(reason)))
        } else SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1))
      }(collection.breakOut)
      inflightRequests = immutable.Queue.empty
      onNext(SlotEvent.Disconnected(slotIx, results.size) :: results)
      if (canceled) onComplete()

      context.become(unconnected)
    }

    override def onComplete(): Unit = {
      exposedPublisher.shutdown(None)
      super.onComplete()
      shutdown()
    }

    override def onError(cause: Throwable): Unit = {
      exposedPublisher.shutdown(Some(cause))
      super.onError(cause)
      shutdown()
    }

    def shutdown(): Unit = {
      context.become { case _ ⇒ }
      context.stop(self)
    }
  }

  private case class FromConnection(ev: Any)

  private class FlowInportActor(slotProcessor: ActorRef) extends ActorPublisher[HttpRequest] {
    def receive: Receive = {
      case ev: Request            ⇒ slotProcessor ! FromConnection(ev)
      case Cancel                 ⇒ { slotProcessor ! FromConnection(Cancel); context.stop(self) }
      case OnNext(r: HttpRequest) ⇒ onNext(r)
      case OnComplete             ⇒ { onComplete(); context.stop(self) }
      case OnError(e)             ⇒ { onError(e); context.stop(self) }
    }
  }

  private class FlowOutportActor(slotProcessor: ActorRef) extends ActorSubscriber {
    def requestStrategy = ZeroRequestStrategy
    def receive: Receive = {
      case Request(n)                     ⇒ request(n)
      case Cancel                         ⇒ cancel()
      case ev: OnNext                     ⇒ slotProcessor ! FromConnection(ev)
      case ev @ (OnComplete | OnError(_)) ⇒ { slotProcessor ! FromConnection(ev); context.stop(self) }
    }
  }

  // FIXME: remove when #17038 is cleared
  private class SlotEventSplit extends FlexiRoute[ProcessorOut, FanOutShape2[ProcessorOut, ResponseContext, SlotEvent]](
    new FanOutShape2("HostConnectionPoolSlot.SlotEventSplit"),
    OperationAttributes.name("HostConnectionPoolSlot.SlotEventSplit")) {
    import FlexiRoute._

    def createRouteLogic(s: FanOutShape2[ProcessorOut, ResponseContext, SlotEvent]): RouteLogic[ProcessorOut] =
      new RouteLogic[ProcessorOut] {
        def initialState: State[_] =
          State(DemandFromAll(s)) { (ctx, _, ev) ⇒
            ev match {
              case ResponseDelivery(x) ⇒ ctx.emit(s.out0)(x)
              case x: SlotEvent        ⇒ ctx.emit(s.out1)(x)
            }
            SameState
          }
      }
  }
}
