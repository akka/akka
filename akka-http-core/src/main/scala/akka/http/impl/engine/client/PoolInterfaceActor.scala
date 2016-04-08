/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.net.InetSocketAddress

import akka.actor._
import akka.http.impl.engine.client.PoolFlow._
import akka.http.scaladsl.model._
import akka.http.scaladsl.{ Http, HttpsConnectionContext }
import akka.stream.actor.ActorPublisherMessage._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.actor.{ ActorPublisher, ActorSubscriber, ZeroRequestStrategy }
import akka.stream.impl.{ Buffer, SeqActorName }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ BufferOverflowException, Materializer }

import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

private object PoolInterfaceActor {
  final case class PoolRequest(request: HttpRequest, responsePromise: Promise[HttpResponse]) extends NoSerializationVerificationNeeded

  case object Shutdown extends DeadLetterSuppression

  val name = SeqActorName("PoolInterfaceActor")
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
private class PoolInterfaceActor(gateway: PoolGateway)(implicit fm: Materializer)
  extends ActorSubscriber with ActorPublisher[RequestContext] with ActorLogging {
  import PoolInterfaceActor._

  private[this] val hcps = gateway.hcps
  private[this] val inputBuffer = Buffer[PoolRequest](hcps.setup.settings.maxOpenRequests, fm)
  private[this] var activeIdleTimeout: Option[Cancellable] = None

  log.debug("(Re-)starting host connection pool to {}:{}", hcps.host, hcps.port)

  initConnectionFlow()

  /** Start the pool flow with this actor acting as source as well as sink */
  private def initConnectionFlow() = {
    import context.system
    import hcps._
    import setup._

    val connectionFlow = connectionContext match {
      case httpsContext: HttpsConnectionContext ⇒ Http().outgoingConnectionHttps(host, port, httpsContext, None, settings.connectionSettings, setup.log)
      case _                                    ⇒ Http().outgoingConnection(host, port, None, settings.connectionSettings, setup.log)
    }

    val poolFlow = PoolFlow(
      Flow[HttpRequest].viaMat(connectionFlow)(Keep.right),
      new InetSocketAddress(host, port), settings, setup.log)
      .named("PoolFlow")

    Source.fromPublisher(ActorPublisher(self)).via(poolFlow).runWith(Sink.fromSubscriber(ActorSubscriber[ResponseContext](self)))
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
      self ! PoisonPill // give potentially queued requests another chance to be forwarded back to the gateway

    case OnError(e) ⇒ // the pool shut down
      log.debug("Host connection pool to {}:{} has shut down with error {}", hcps.host, hcps.port, e)
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
            new BufferOverflowException(s"Exceeded configured max-open-requests value of [${inputBuffer.capacity}]"))
        } else inputBuffer.enqueue(x)
      } else dispatchRequest(x) // if we can dispatch right now, do it
      request(1) // for every incoming request we demand one response from the pool

    case PoolRequest(request, responsePromise) ⇒
      // we have already started shutting down, i.e. this pool is not usable anymore
      // so we forward the request back to the gateway
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
    val scheme = Uri.httpScheme(hcps.setup.connectionContext.isSecure)
    val hostHeader = headers.Host(hcps.host, Uri.normalizePort(hcps.port, scheme))
    val effectiveRequest =
      pr.request
        .withUri(pr.request.uri.toHttpRequestTargetOriginForm)
        .withDefaultHeaders(hostHeader)
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
