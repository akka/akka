/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.util.concurrent.atomic.AtomicReference
import akka.Done

import scala.annotation.tailrec
import scala.concurrent.{ Future, Promise }
import akka.http.impl.settings.HostConnectionPoolSetup
import akka.actor.{ Deploy, Props, ActorSystem, ActorRef }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.stream.Materializer

private object PoolGateway {

  sealed trait State
  final case class Running(interfaceActorRef: ActorRef,
                           shutdownStartedPromise: Promise[Done],
                           shutdownCompletedPromise: Promise[Done]) extends State
  final case class IsShutdown(shutdownCompleted: Future[Done]) extends State
  final case class NewIncarnation(gatewayFuture: Future[PoolGateway]) extends State
}

/**
 * Manages access to a host connection pool or rather: a sequence of pool incarnations.
 *
 * A host connection pool for a given [[HostConnectionPoolSetup]] is a running stream, whose outside interface is
 * provided by its [[PoolInterfaceActor]] actor. The actor accepts [[PoolInterfaceActor.PoolRequest]] messages
 * and completes their `responsePromise` whenever the respective response has been received (or an error occurred).
 *
 * A [[PoolGateway]] provides a layer of indirection between the pool cache and the actual
 * pools that is required to allow a pool incarnation to fully terminate (e.g. after an idle-timeout)
 * and be transparently replaced by a new incarnation if required.
 * Removal of cache entries for terminated pools is also supported, because old gateway references that
 * get reused will automatically forward requests directed at them to the latest pool incarnation from the cache.
 */
private[http] class PoolGateway(hcps: HostConnectionPoolSetup,
                                _shutdownStartedPromise: Promise[Done])( // constructor arg only
                                  implicit system: ActorSystem, fm: Materializer) {
  import PoolGateway._
  import fm.executionContext

  private val state = {
    val shutdownCompletedPromise = Promise[Done]()
    val props = Props(new PoolInterfaceActor(hcps, shutdownCompletedPromise, this)).withDeploy(Deploy.local)
    val ref = system.actorOf(props, PoolInterfaceActor.name.next())
    new AtomicReference[State](Running(ref, _shutdownStartedPromise, shutdownCompletedPromise))
  }

  def currentState: Any = state.get() // enables test access

  def apply(request: HttpRequest, previousIncarnation: PoolGateway = null): Future[HttpResponse] =
    state.get match {
      case Running(ref, _, _) ⇒
        val responsePromise = Promise[HttpResponse]()
        ref ! PoolInterfaceActor.PoolRequest(request, responsePromise)
        responsePromise.future

      case IsShutdown(shutdownCompleted) ⇒
        // delay starting the next pool incarnation until the current pool has completed its shutdown
        shutdownCompleted.flatMap { _ ⇒
          val newGatewayFuture = Http().cachedGateway(hcps)
          // a simple set is fine here as `newGatewayFuture` will be identical for all threads getting here
          state.set(NewIncarnation(newGatewayFuture))
          apply(request)
        }

      case x @ NewIncarnation(newGatewayFuture) ⇒
        if (previousIncarnation != null)
          previousIncarnation.state.set(x) // collapse incarnation chain
        newGatewayFuture.flatMap(_(request, this))
    }

  // triggers a shutdown of the current pool, even if it is already a later incarnation
  @tailrec final def shutdown(): Future[Done] =
    state.get match {
      case x @ Running(ref, shutdownStartedPromise, shutdownCompletedPromise) ⇒
        if (state.compareAndSet(x, IsShutdown(shutdownCompletedPromise.future))) {
          shutdownStartedPromise.success(Done) // trigger cache removal
          ref ! PoolInterfaceActor.Shutdown
          shutdownCompletedPromise.future
        } else shutdown() // CAS loop (not a spinlock)

      case IsShutdown(x)                    ⇒ x

      case NewIncarnation(newGatewayFuture) ⇒ newGatewayFuture.flatMap(_.shutdownAux())
    }

  private def shutdownAux() = shutdown() // alias required for @tailrec
}