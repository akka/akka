/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.util.concurrent.atomic.AtomicLong

import akka.Done
import akka.actor.ActorRef
import akka.http.impl.engine.client.PoolGateway.{ GatewayIdentifier, SharedGateway }
import akka.http.impl.engine.client.PoolMasterActor._
import akka.http.impl.settings.HostConnectionPoolSetup
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer

import scala.concurrent.{ Future, Promise }

/**
 * Manages access to a host connection pool through the [[PoolMasterActor]]
 *
 * A [[PoolGateway]] is represented by its [[HostConnectionPoolSetup]] and its [[GatewayIdentifier]]. If the later
 * is [[SharedGateway]], it means that a shared pool must be used for this particular [[HostConnectionPoolSetup]].
 */
private[http] final class PoolGateway(gatewayRef: ActorRef, val hcps: HostConnectionPoolSetup, val gatewayId: GatewayIdentifier)(implicit fm: Materializer) {

  /**
   * Send a request through the corresponding pool. If the pool is not running, it will be started
   * automatically. If it is shutting down, it will restart as soon as the shutdown operation is
   * complete and serve this request.
   *
   * @param request the request
   * @return the response
   */
  def apply(request: HttpRequest): Future[HttpResponse] = {
    println(s"== apply(${request})")
    val responsePromise = Promise[HttpResponse]()
    gatewayRef ! SendRequest(this, request, responsePromise, fm)
    responsePromise.future
  }

  /**
   * Start the corresponding pool to make it ready to serve requests. If the pool is already started,
   * this does nothing. If it is being shutdown, it will restart as soon as the shutdown operation
   * is complete.
   *
   * @return the gateway itself
   */
  def startPool(): PoolGateway = {
    println("== startPool()")
    gatewayRef ! StartPool(this, fm)
    this
  }

  /**
   * Shutdown the corresponding pool and signal its termination. If the pool is not running or is
   * being shutting down, this does nothing,
   *
   * @return a Future completed when the pool has been shutdown.
   */
  def shutdown(): Future[Done] = {
    val shutdownCompletedPromise = Promise[Done]()
    gatewayRef ! Shutdown(this, shutdownCompletedPromise)
    shutdownCompletedPromise.future
  }

  override def toString = s"PoolGateway(hcps = $hcps)"

  // INTERNAL API (testing only)
  private[client] def poolStatus(): Future[Option[PoolInterfaceStatus]] = {
    val statusPromise = Promise[Option[PoolInterfaceStatus]]()
    gatewayRef ! PoolStatus(this, statusPromise)
    statusPromise.future
  }

  override def equals(that: Any): Boolean =
    that match {
      case p: PoolGateway ⇒ p.hcps == hcps && p.gatewayId == gatewayId
      case _              ⇒ false
    }

  override def hashCode(): Int = hcps.hashCode() ^ gatewayId.hashCode()
}

private[http] object PoolGateway {

  sealed trait GatewayIdentifier
  case object SharedGateway extends GatewayIdentifier
  final case class UniqueGateway(id: Long) extends GatewayIdentifier

  private[this] val uniqueGatewayId = new AtomicLong(0)
  def newUniqueGatewayIdentifier = UniqueGateway(uniqueGatewayId.incrementAndGet())

}
