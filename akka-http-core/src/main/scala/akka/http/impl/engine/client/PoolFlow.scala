/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.NotUsed
import akka.http.scaladsl.settings.ConnectionPoolSettings

import scala.concurrent.{ Promise, Future }
import scala.util.Try
import akka.event.LoggingAdapter
import akka.actor._
import akka.stream.{ FlowShape, Materializer }
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http

private object PoolFlow {

  case class RequestContext(request: HttpRequest, responsePromise: Promise[HttpResponse], retriesLeft: Int) {
    require(retriesLeft >= 0)
  }
  case class ResponseContext(rc: RequestContext, response: Try[HttpResponse])

  /*
    Pool Flow Stream Setup
    ======================
                                               +-------------------+                             
                                               |                   |                             
                                        +----> | Connection Slot 1 +---->                        
                                        |      |                   |    |                        
                                        |      +---+---------------+    |                        
                                        |          |                    |                        
                       +-----------+    |      +-------------------+    |      +---------------+
      RequestContext   |           +----+      |                   |    +----> |               |  ResponseContext
    +----------------> | Conductor |---------> | Connection Slot 2 +---------> | responseMerge +------------------>
                       |           +----+      |                   |    +----> |               |
                       +-----------+    |      +---------+---------+    |      +---------------+
                             ^          |          |     |              |                        
                             |          |      +-------------------+    |                        
                             |          |      |                   |    |                        
                RawSlotEvent |          +----> | Connection Slot 3 +---->                        
                             |                 |                   |                             
                             |                 +---------------+---+                             
                             |                     |     |     |                                    
                       +-----------+  RawSlotEvent |     |     | 
                       | slotEvent | <-------------+     |     |
                       |   Merge   | <-------------------+     |                                  
                       |           | <-------------------------+                                  
                       +-----------+

    Conductor:
    - Maintains slot state overview by running a simple state machine per Connection Slot
    - Decides which slot will receive the next request from upstream according to current slot state and dispatch configuration
    - Forwards demand from selected slot to upstream
    - Always maintains demand for SlotEvents from the Connection Slots
    - Implemented as a sub-graph

    Connection Slot:
    - Wraps a low-level outgoing connection flow and (re-)materializes and uses it whenever necessary
    - Directly forwards demand from the underlying connection to the Conductor
    - Dispatches SlotEvents to the Conductor (via the SlotEventMerge)
    - Implemented as a sub-graph

    Response Merge:
    - Simple merge of the Connection Slots' outputs

  */
  def apply(
    connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
    settings:       ConnectionPoolSettings, log: LoggingAdapter)(
    implicit
    system: ActorSystem, fm: Materializer): Flow[RequestContext, ResponseContext, NotUsed] =
    Flow.fromGraph(GraphDSL.create[FlowShape[RequestContext, ResponseContext]]() { implicit b ⇒
      import settings._
      import GraphDSL.Implicits._

      val conductor = b.add(PoolConductor(maxConnections, pipeliningLimit, log))
      val slots = Vector
        .tabulate(maxConnections)(PoolSlot(_, connectionFlow, settings))
        .map(b.add)
      val responseMerge = b.add(Merge[ResponseContext](maxConnections))
      val slotEventMerge = b.add(Merge[PoolSlot.RawSlotEvent](maxConnections))

      slotEventMerge.out ~> conductor.slotEventIn
      for ((slot, ix) ← slots.zipWithIndex) {
        conductor.slotOuts(ix) ~> slot.in
        slot.out0 ~> responseMerge.in(ix)
        slot.out1 ~> slotEventMerge.in(ix)
      }
      FlowShape(conductor.requestIn, responseMerge.out)
    })
}
