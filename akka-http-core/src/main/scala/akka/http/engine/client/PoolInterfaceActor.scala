/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import akka.actor.{ PoisonPill, DeadLetterSuppression, ActorLogging, Cancellable }
import akka.stream.FlowMaterializer
import akka.stream.actor.{ ActorPublisher, ActorSubscriber, ZeroRequestStrategy }
import akka.stream.actor.ActorPublisherMessage._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.impl.FixedSizeBuffer
import akka.stream.scaladsl.{ Sink, Source }
import akka.http.model.{ HttpResponse, HttpRequest }
import akka.http.util.SeqActorName
import akka.http.Http
import PoolFlow._

private object PoolInterfaceActor {
  final case class PoolRequest(request: HttpRequest, responsePromise: Promise[HttpResponse])

  case object Shutdown extends DeadLetterSuppression

  val name = new SeqActorName("PoolInterfaceActor")
}

/**
 * An actor that wraps a completely encapsulated, running connection pool flow.
 *
 * Outside interface:
 *   The actor accepts `PoolRequest` messages and completes their `responsePromise` when the respective
 *   response has arrived. Incoming `PoolRequest` messages are not back-pressured but rather buffered in
 *   a fixed-size ringbuffer if required. Requests that would cause a buffer overflow are completed with
 *   a respective error. The user can prevent buffer overflows by configuring a `max-open-requests` value
 *   that is >= max-connections x pipelining-limit x number of respective client-flow materializations.
 *
 * Inside interface:
 *   To the inside (i.e. the running connection pool flow) the gateway actor acts as request source
 *   (ActorPublisher) and response sink (ActorSubscriber).
 */
private class PoolInterfaceActor(hcps: HostConnectionPoolSetup,
                                 shutdownCompletedPromise: Promise[Unit],
                                 gateway: PoolGateway)(implicit fm: FlowMaterializer)
  extends ActorSubscriber with ActorPublisher[RequestContext] with ActorLogging {
  import PoolInterfaceActor._

  private[this] val inputBuffer = FixedSizeBuffer[PoolRequest](hcps.setup.settings.maxOpenRequests)
  private[this] var activeIdleTimeout: Option[Cancellable] = None

  log.debug("(Re-)starting host connection pool to {}:{}", hcps.host, hcps.port)

  { // start the pool flow with this actor acting as source as well as sink
    import context.system
    import hcps._
    import setup._
    val connectionFlow = Http().outgoingConnection(host, port, None, options, settings.connectionSettings, setup.log)
    val poolFlow = PoolFlow(connectionFlow, new InetSocketAddress(host, port), settings, setup.log)
    Source(ActorPublisher(self)).via(poolFlow).runWith(Sink(ActorSubscriber[ResponseContext](self)))
  }

  activateIdleTimeoutIfNecessary()

  def requestStrategy = ZeroRequestStrategy

  def receive = {

    /////////////// COMING UP FROM POOL (SOURCE SIDE) //////////////

    case Request(_) ⇒ dispatchRequests() // the pool is ready to take on more requests

    case Cancel     ⇒
    // somehow the pool shut down, however, we don't do anything here because we'll also see an
    // OnComplete or OnError which we use as the sole trigger for cleaning up

    /////////////// COMING DOWN FROM POOL (SINK SIDE) //////////////

    case OnNext(ResponseContext(rc, responseTry)) ⇒
      rc.responsePromise.complete(responseTry)
      activateIdleTimeoutIfNecessary()

    case OnComplete ⇒ // the pool shut down
      log.debug("Host connection pool to {}:{} has completed orderly shutdown", hcps.host, hcps.port)
      shutdownCompletedPromise.success(())
      self ! PoisonPill // give potentially queued requests another chance to be forwarded back to the gateway

    case OnError(e) ⇒ // the pool shut down
      log.debug("Host connection pool to {}:{} has shut down with error {}", hcps.host, hcps.port, e)
      shutdownCompletedPromise.failure(e)
      self ! PoisonPill // give potentially queued requests another chance to be forwarded back to the gateway

    /////////////// FROM CLIENT //////////////

    case x: PoolRequest if isActive ⇒
      activeIdleTimeout foreach { timeout ⇒
        timeout.cancel()
        activeIdleTimeout = None
      }
      if (totalDemand == 0) {
        // if we can't dispatch right now we buffer and dispatch when demand from the pool arrives
        if (inputBuffer.isFull) {
          x.responsePromise.failure(
            new RuntimeException(s"Exceeded configured max-open-requests value of [${inputBuffer.size}}]"))
        } else inputBuffer.enqueue(x)
      } else dispatchRequest(x) // if we can dispatch right now, do it
      request(1) // for every incoming request we demand one response from the pool

    case PoolRequest(request, responsePromise) ⇒
      // we have already started shutting down, i.e. this pool is not usable anymore
      // so we forward the request back to the gateway
      // Note that this forwarding will stop when we receive completion from the pool flow
      // (because we stop ourselves then), so there is a very small chance of a request ending
      // up as a dead letter if the sending thread gets interrupted for a long time right before
      // the `ref ! PoolRequest(...)` in the PoolGateway
      responsePromise.completeWith(gateway(request))

    case Shutdown ⇒ // signal coming in from gateway
      log.debug("Shutting down host connection pool to {}:{}", hcps.host, hcps.port)
      onComplete()
      while (!inputBuffer.isEmpty) {
        val PoolRequest(request, responsePromise) = inputBuffer.dequeue()
        responsePromise.completeWith(gateway(request))
      }
  }

  @tailrec private def dispatchRequests(): Unit =
    if (totalDemand > 0 && !inputBuffer.isEmpty) {
      dispatchRequest(inputBuffer.dequeue())
      dispatchRequests()
    }

  def dispatchRequest(pr: PoolRequest): Unit = {
    val effectiveRequest = pr.request.withUri(pr.request.uri.toHttpRequestTargetOriginForm)
    val retries = if (pr.request.method.isIdempotent) hcps.setup.settings.maxRetries else 0
    onNext(RequestContext(effectiveRequest, pr.responsePromise, retries))
  }

  def activateIdleTimeoutIfNecessary(): Unit =
    if (remainingRequested == 0 && hcps.setup.settings.idleTimeout.isFinite) {
      import context.dispatcher
      val timeout = hcps.setup.settings.idleTimeout.asInstanceOf[FiniteDuration]
      activeIdleTimeout = Some(context.system.scheduler.scheduleOnce(timeout)(gateway.shutdown()))
    }
}
