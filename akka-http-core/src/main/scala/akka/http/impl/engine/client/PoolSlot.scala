/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import language.existentials
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.collection.immutable
import akka.actor._
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse, HttpRequest }
import akka.http.scaladsl.util.FastFuture
import akka.http.ConnectionPoolSettings
import akka.http.impl.util._
import akka.stream.impl.{ SubscribePending, ExposedPublisher, ActorProcessor }
import akka.stream.actor._
import akka.stream.scaladsl._
import akka.stream._

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
  }

  private val slotProcessorActorName = new SeqActorName("SlotProcessor")

  /*
    Stream Setup
    ============

    Request-   +-----------+              +-------------+              +-------------+     +------------+ 
    Context    | Slot-     |  List[       |   flatten   |  Processor-  |   doubler   |     | SlotEvent- |  Response-
    +--------->| Processor +------------->| (MapConcat) +------------->| (MapConcat) +---->| Split      +------------->
               |           |  Processor-  |             |  Out         |             |     |            |  Context                                  
               +-----------+  Out]        +-------------+              +-------------+     +-----+------+                                    
                                                                                                 | RawSlotEvent                                                    
                                                                                                 | (to Conductor
                                                                                                 |  via slotEventMerge)
                                                                                                 v 
   */
  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
            remoteAddress: InetSocketAddress, // TODO: remove after #16168 is cleared
            settings: ConnectionPoolSettings)(implicit system: ActorSystem,
                                              fm: Materializer): Graph[FanOutShape2[RequestContext, ResponseContext, RawSlotEvent], Any] =
    FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._

      val slotProcessor = b.add {
        Flow[RequestContext].andThenMat { () ⇒
          val actor = system.actorOf(Props(new SlotProcessor(slotIx, connectionFlow, settings)).withDeploy(Deploy.local),
            slotProcessorActorName.next())
          (ActorProcessor[RequestContext, List[ProcessorOut]](actor), ())
        }.mapConcat(identity)
      }
      val split = b.add(Broadcast[ProcessorOut](2))

      slotProcessor ~> split.in

      new FanOutShape2(slotProcessor.inlet,
        split.out(0).collect { case ResponseDelivery(r) ⇒ r }.outlet,
        split.out(1).collect { case r: RawSlotEvent ⇒ r }.outlet)
    }

  import ActorSubscriberMessage._
  import ActorPublisherMessage._

  /**
   * An actor mananging a series of materializations of the given `connectionFlow`.
   * To the outside it provides a stable flow stage, consuming `RequestContext` instances on its
   * input (ActorSubscriber) side and producing `List[ProcessorOut]` instances on its output
   * (ActorPublisher) side.
   * The given `connectionFlow` is materialized into a running flow whenever required.
   * Completion and errors from the connection are not surfaced to the outside (unless we are
   * shutting down completely).
   */
  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
                              settings: ConnectionPoolSettings)(implicit fm: Materializer)
    extends ActorSubscriber with ActorPublisher[List[ProcessorOut]] with ActorLogging {

    var exposedPublisher: akka.stream.impl.ActorPublisher[Any] = _
    var inflightRequests = immutable.Queue.empty[RequestContext]
    val runnableGraph = Source.actorPublisher[HttpRequest](Props(new FlowInportActor(self)).withDeploy(Deploy.local))
      .via(connectionFlow)
      .toMat(Sink.actorSubscriber[HttpResponse](Props(new FlowOutportActor(self)).withDeploy(Deploy.local)))(Keep.both)

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

    val unconnected: Receive = {
      case OnNext(rc: RequestContext) ⇒
        val (connInport, connOutport) = runnableGraph.run()
        connOutport ! Request(totalDemand)
        context.become(waitingForDemandFromConnection(connInport, connOutport, rc))

      case Request(_) ⇒ if (remainingRequested == 0) request(1) // ask for first request if necessary

      case Cancel     ⇒ { cancel(); shutdown() }
      case OnComplete ⇒ onComplete()
      case OnError(e) ⇒ onError(e)
    }

    def waitingForDemandFromConnection(connInport: ActorRef, connOutport: ActorRef,
                                       firstRequest: RequestContext): Receive = {
      case ev @ (Request(_) | Cancel)     ⇒ connOutport ! ev
      case ev @ (OnComplete | OnError(_)) ⇒ connInport ! ev
      case OnNext(x)                      ⇒ throw new IllegalStateException("Unrequested RequestContext: " + x)

      case FromConnection(Request(n)) ⇒
        inflightRequests = inflightRequests.enqueue(firstRequest)
        request(n - remainingRequested)
        connInport ! OnNext(firstRequest.request)
        context.become(running(connInport, connOutport))

      case FromConnection(Cancel)     ⇒ if (!isActive) { cancel(); shutdown() } // else ignore and wait for accompanying OnComplete or OnError
      case FromConnection(OnComplete) ⇒ handleDisconnect(None)
      case FromConnection(OnError(e)) ⇒ handleDisconnect(Some(e))
      case FromConnection(OnNext(x))  ⇒ throw new IllegalStateException("Unexpected HttpResponse: " + x)
    }

    def running(connInport: ActorRef, connOutport: ActorRef): Receive = {
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
        val (entity, whenCompleted) = response.entity match {
          case x: HttpEntity.Strict ⇒ x -> FastFuture.successful(())
          case x: HttpEntity.Default ⇒
            val (newData, whenCompleted) = StreamUtils.captureTermination(x.data)
            x.copy(data = newData) -> whenCompleted
          case x: HttpEntity.CloseDelimited ⇒
            val (newData, whenCompleted) = StreamUtils.captureTermination(x.data)
            x.copy(data = newData) -> whenCompleted
          case x: HttpEntity.Chunked ⇒
            val (newChunks, whenCompleted) = StreamUtils.captureTermination(x.chunks)
            x.copy(chunks = newChunks) -> whenCompleted
        }
        val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response withEntity entity)))
        import fm.executionContext
        val requestCompleted = SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx)))
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

    def shutdown(): Unit = context.stop(self)
  }

  private case class FromConnection(ev: Any) extends NoSerializationVerificationNeeded

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
}
