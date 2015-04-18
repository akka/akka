/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.annotation.tailrec
import scala.concurrent.{ Promise, Future }
import akka.event.LoggingAdapter
import akka.actor._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.http.model._
import akka.http.util._
import akka.http.Http

private[http] object ConnectionPool {
  import HostConnectionPoolGateway._

  case class PoolRequest(request: HttpRequest, responsePromise: Promise[HttpResponse])
  case object BeginShutdown extends DeadLetterSuppression

  /**
   * Starts a new host connection pool and returns its gateway actor.
   *
   * A host connection pool for a given [[HostConnectionPoolSetup]] is a running stream, whose outside interface is
   * provided by its [[PoolGateway]] actor.
   * This gateway actor accepts [[PoolRequest]] messages and completes their `responsePromise` whenever the respective
   * response has been received (or an error occurred).
   * A gateway actor can be orderly shut down by sending it a [[BeginShutdown]] message.
   *
   * A running host connection pool automatically shuts down itself after the configured idle timeout.
   */
  def startHostConnectionPool(hcps: HostConnectionPoolSetup)(implicit system: ActorSystem,
                                                             fm: FlowMaterializer): Gateway = {
    import hcps._
    import setup._
    val connectionFlow = Http().outgoingConnection(host, port, None, options, Some(settings.connectionSettings), log)
    val poolFlow = newPoolFlow(connectionFlow, new InetSocketAddress(host, port), settings, log)
    val gateway = new Gateway(new PoolGateway(poolFlow, hcps, _))
    gateway.actorRef() // trigger eager start of pool gateway (and pool)
    gateway
  }

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
  private def newPoolFlow(connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
                          remoteAddress: InetSocketAddress, settings: HostConnectionPoolSettings, log: LoggingAdapter)(
                            implicit system: ActorSystem, fm: FlowMaterializer): Flow[RequestContext, ResponseContext, Any] =
    Flow() { implicit b ⇒
      import settings._
      import FlowGraph.Implicits._

      val conductor = b.add(HostConnectionPoolConductor(maxConnections, maxRetries, pipeliningLimit, log))
      val slots = Vector
        .tabulate(maxConnections)(HostConnectionPoolSlot(_, connectionFlow, remoteAddress, settings))
        .map(b.add(_))
      val responseMerge = b.add(Merge[ResponseContext](maxConnections,
        OperationAttributes.name("HostConnectionPoolFlow.responseMerge")))
      val slotEventMerge = b.add(Merge[HostConnectionPoolSlot.SlotEvent](maxConnections,
        OperationAttributes.name("HostConnectionPoolFlow.slotEventMerge")))

      slotEventMerge.out ~> conductor.slotEventIn
      for ((slot, ix) ← slots.zipWithIndex) {
        conductor.slotOuts(ix) ~> slot.requestContextIn
        slot.slotEventOut ~> slotEventMerge.in(ix)
        slot.responseOut ~> responseMerge.in(ix)
      }
      (conductor.requestIn, responseMerge.out)
    }

  // for reigning in potentially approaching thundering herds
  val CowboyGateway = new Gateway(null)(null)
  private val CowboyGatewayRef = new GateWayRef(null, null)

  private val poolGatewayActorName = new SeqActorName("PoolGateway")

  final class Gateway(actorCreator: Promise[Future[Unit]] ⇒ PoolGateway)(implicit system: ActorSystem) {
    private[this] val cell = new AtomicReference(new GateWayRef(null, Promise.successful(Future.successful(()))))

    // completed with a "shutdown-finished" future when the shutdown is started
    def whenShuttingDown: Future[Future[Unit]] = cell.get.whenShuttingDown.future

    // triggers a shutdown of this gateways pool
    def shutdown(): Future[Unit] = {
      val fut = whenShuttingDown
      if (!fut.isCompleted) // try to avoid a restart just for shutting down
        actorRef() ! ConnectionPool.BeginShutdown
      fut.flatMap(identityFunc)(system.dispatcher)
    }

    // retrieves the ActorRef for the running Gateway actor
    // or starts a new one it if the gateway is already in (or finished with) the shutdown process
    @tailrec def actorRef(): ActorRef = {
      var ref = cell.get
      if (ref ne CowboyGatewayRef) {
        if (ref.whenShuttingDown.isCompleted) {
          if (cell.compareAndSet(ref, CowboyGatewayRef)) {
            try {
              val promise = Promise[Future[Unit]]()
              val gatewayActorRef = system.actorOf(Props(actorCreator(promise)), poolGatewayActorName.next())
              ref = new GateWayRef(gatewayActorRef, promise)
              gatewayActorRef
            } finally {
              cell.set(ref)
            }
          } else actorRef() // spin while other thread is starting a new gateway actor
        } else ref.actorRef // gateway actor hasn't started the shutdown process until recently
      } else actorRef() // spin while other thread is starting a new gateway actor
    }
  }

  private final class GateWayRef(val actorRef: ActorRef, val whenShuttingDown: Promise[Future[Unit]])
}
