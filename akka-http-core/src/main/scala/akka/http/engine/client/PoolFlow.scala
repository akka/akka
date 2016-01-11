/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import scala.concurrent.{ Promise, Future }
import scala.util.Try
import akka.event.LoggingAdapter
import akka.actor._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.http.model._
import akka.http.Http

private object PoolFlow {

  case class RequestContext(request: HttpRequest, responsePromise: Promise[HttpResponse], retriesLeft: Int) {
    require(retriesLeft >= 0)
  }
  case class ResponseContext(rc: RequestContext, response: Try[HttpResponse])

  /*
    Pool Flow Stream Setup
    ======================
                                               +-------------------+                             
                                               |                   |                             
                                        +----> | Connection Slot 1 +---->                        
                                        |      |                   |    |                        
                                        |      +---+---------------+    |                        
                                        |          |                    |                        
                       +-----------+    |      +-------------------+    |      +---------------+
      RequestContext   |           +----+      |                   |    +----> |               |  ResponseContext
    +----------------> | Conductor |---------> | Connection Slot 2 +---------> | responseMerge +------------------>
                       |           +----+      |                   |    +----> |               |
                       +-----------+    |      +---------+---------+    |      +---------------+
                             ^          |          |     |              |                        
                             |          |      +-------------------+    |                        
                             |          |      |                   |    |                        
                   SlotEvent |          +----> | Connection Slot 3 +---->                        
                             |                 |                   |                             
                             |                 +---------------+---+                             
                             |                     |     |     |                                    
                       +-----------+    SlotEvent  |     |     | 
                       | slotEvent | <-------------+     |     |
                       |   Merge   | <-------------------+     |                                  
                       |           | <-------------------------+                                  
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
  def apply(connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
            remoteAddress: InetSocketAddress, settings: ConnectionPoolSettings, log: LoggingAdapter)(
              implicit system: ActorSystem, fm: FlowMaterializer): Flow[RequestContext, ResponseContext, Unit] =
    Flow() { implicit b ⇒
      import settings._
      import FlowGraph.Implicits._

      val conductor = b.add(PoolConductor(maxConnections, maxRetries, pipeliningLimit, log))
      val slots = Vector
        .tabulate(maxConnections)(PoolSlot(_, connectionFlow, remoteAddress, settings))
        .map(b.add(_))
      val responseMerge = b.add(Merge[ResponseContext](maxConnections))
      val slotEventMerge = b.add(Merge[PoolSlot.SlotEvent](maxConnections))

      slotEventMerge.out ~> conductor.slotEventIn
      for ((slot, ix) ← slots.zipWithIndex) {
        conductor.slotOuts(ix) ~> slot.in
        slot.out0 ~> responseMerge.in(ix)
        slot.out1 ~> slotEventMerge.in(ix)
      }
      (conductor.requestIn, responseMerge.out)
    }
}
