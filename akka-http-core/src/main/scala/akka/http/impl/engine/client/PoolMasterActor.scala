/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.Done
import akka.actor.{ Actor, ActorLogging, ActorRef, DeadLetterSuppression, Deploy, NoSerializationVerificationNeeded, Props, Terminated }
import akka.http.impl.engine.client.PoolInterfaceActor.PoolRequest
import akka.http.impl.settings.HostConnectionPoolSetup
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer

import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 *
 * Manages access to a host connection pool or rather: a sequence of pool incarnations.
 *
 * A host connection pool for a given [[HostConnectionPoolSetup]] is a running stream, whose outside interface is
 * provided by its [[PoolInterfaceActor]] actor. The actor accepts [[PoolInterfaceActor.PoolRequest]] messages
 * and completes their `responsePromise` whenever the respective response has been received (or an error occurred).
 *
 * The [[PoolMasterActor]] provides a layer of indirection between a [[PoolGateway]], which represents a pool,
 * and the [[PoolInterfaceActor]] instances which are created on-demand and stopped after an idle-timeout.
 *
 * Several [[PoolGateway]] objects may be mapped to the same pool if they have the same [[HostConnectionPoolSetup]]
 * and are marked as being shared. This is the case for example for gateways obtained through
 * [[HttpExt.cachedHostConnectionPool]]. Some other gateways are not shared, such as those obtained through
 * [[HttpExt.newHostConnectionPool]], and will have their dedicated restartable pool.
 *
 */
private[http] final class PoolMasterActor extends Actor with ActorLogging {

  import PoolMasterActor._

  private[this] var poolStatus = Map[PoolGateway, PoolInterfaceStatus]()
  private[this] var poolInterfaces = Map[ActorRef, PoolGateway]()

  /**
   * Start a new pool interface actor, register it in our maps, and watch its death. No actor should
   * currently exist for this pool.
   *
   * @param gateway the pool gateway this pool corresponds to
   * @param fm the materializer to use for this pool
   * @return the newly created actor ref
   */
  private[this] def startPoolInterfaceActor(gateway: PoolGateway)(implicit fm: Materializer): ActorRef = {
    if (poolStatus.contains(gateway)) {
      throw new IllegalStateException(s"pool interface actor for $gateway already exists")
    }
    val props = Props(new PoolInterfaceActor(gateway)).withDeploy(Deploy.local)
    val ref = context.actorOf(props, PoolInterfaceActor.name.next())
    poolStatus += gateway → PoolInterfaceRunning(ref)
    poolInterfaces += ref → gateway
    context.watch(ref)
  }

  def receive = {

    // Start or restart a pool without sending it a request. This is used to ensure that
    // freshly created pools will be ready to serve requests immediately.
    case s @ StartPool(gateway, materializer) ⇒
      poolStatus.get(gateway) match {
        case Some(PoolInterfaceRunning(_)) ⇒
        case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) ⇒
          // Pool is being shutdown. When this is done, start the pool again.
          shutdownCompletedPromise.future.onComplete(_ ⇒ self ! s)(context.dispatcher)
        case None ⇒
          startPoolInterfaceActor(gateway)(materializer)
      }

    // Send a request to a pool. If needed, the pool will be started or restarted.
    case s @ SendRequest(gateway, request, responsePromise, materializer) ⇒
      poolStatus.get(gateway) match {
        case Some(PoolInterfaceRunning(ref)) ⇒
          ref ! PoolRequest(request, responsePromise)
        case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) ⇒
          // The request will be resent when the pool shutdown is complete (the first
          // request will recreate the pool).
          shutdownCompletedPromise.future.foreach(_ ⇒ self ! s)(context.dispatcher)
        case None ⇒
          startPoolInterfaceActor(gateway)(materializer) ! PoolRequest(request, responsePromise)
      }

    // Shutdown a pool and signal its termination.
    case Shutdown(gateway, shutdownCompletedPromise) ⇒
      poolStatus.get(gateway).foreach {
        case PoolInterfaceRunning(ref) ⇒
          // Ask the pool to shutdown itself. Queued connections will be resent here
          // to this actor by the pool actor, they will be retried once the shutdown
          // has completed.
          ref ! PoolInterfaceActor.Shutdown
          poolStatus += gateway → PoolInterfaceShuttingDown(shutdownCompletedPromise)
        case PoolInterfaceShuttingDown(formerPromise) ⇒
          // Pool is already shutting down, mirror the existing promise.
          shutdownCompletedPromise.tryCompleteWith(formerPromise.future)
        case _ ⇒
          // Pool does not exist, shutdown is not needed.
          shutdownCompletedPromise.trySuccess(Done)
      }

    // Shutdown all known pools and signal their termination.
    case ShutdownAll(shutdownCompletedPromise) ⇒
      import context.dispatcher
      def track(remaining: Iterator[Future[Done]]): Unit =
        if (remaining.hasNext) remaining.next().onComplete(_ ⇒ track(remaining))
        else shutdownCompletedPromise.trySuccess(Done)
      track(poolStatus.keys.map(_.shutdown()).toIterator)

    // When a pool actor terminate, signal its termination and remove it from our maps.
    case Terminated(ref) ⇒
      poolInterfaces.get(ref).foreach { gateway ⇒
        poolStatus.get(gateway) match {
          case Some(PoolInterfaceRunning(_)) ⇒
            log.error("connection pool for {} has shut down unexpectedly", gateway)
          case Some(PoolInterfaceShuttingDown(shutdownCompletedPromise)) ⇒
            shutdownCompletedPromise.trySuccess(Done)
          case None ⇒
          // This will never happen as poolInterfaces and poolStatus are modified
          // together. If there is no status then there is no gateway to start with.
        }
        poolStatus -= gateway
        poolInterfaces -= ref
      }

    // Testing only.
    case PoolStatus(gateway, statusPromise) ⇒
      statusPromise.success(poolStatus.get(gateway))

    // Testing only.
    case PoolSize(sizePromise) ⇒
      sizePromise.success(poolStatus.size)

  }

}

private[http] object PoolMasterActor {

  val props = Props[PoolMasterActor].withDeploy(Deploy.local)

  sealed trait PoolInterfaceStatus
  final case class PoolInterfaceRunning(ref: ActorRef) extends PoolInterfaceStatus
  final case class PoolInterfaceShuttingDown(shutdownCompletedPromise: Promise[Done]) extends PoolInterfaceStatus

  final case class StartPool(gateway: PoolGateway, materializer: Materializer) extends NoSerializationVerificationNeeded
  final case class SendRequest(gateway: PoolGateway, request: HttpRequest, responsePromise: Promise[HttpResponse], materializer: Materializer)
    extends NoSerializationVerificationNeeded
  final case class Shutdown(gateway: PoolGateway, shutdownCompletedPromise: Promise[Done]) extends NoSerializationVerificationNeeded with DeadLetterSuppression
  final case class ShutdownAll(shutdownCompletedPromise: Promise[Done]) extends NoSerializationVerificationNeeded with DeadLetterSuppression

  // INTERNAL API (for testing only)
  final case class PoolStatus(gateway: PoolGateway, statusPromise: Promise[Option[PoolInterfaceStatus]]) extends NoSerializationVerificationNeeded
  final case class PoolSize(sizePromise: Promise[Int]) extends NoSerializationVerificationNeeded

}
