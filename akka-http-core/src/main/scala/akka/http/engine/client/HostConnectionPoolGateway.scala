/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import akka.actor.Cancellable
import akka.stream.FlowMaterializer
import akka.stream.actor.{ ActorPublisher, ActorSubscriber, ZeroRequestStrategy }
import akka.stream.actor.ActorPublisherMessage._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.scaladsl.{ Sink, Source, Flow }
import akka.http.model._
import akka.http.util._

private object HostConnectionPoolGateway {
  import ConnectionPool.{ PoolRequest, BeginShutdown }

  case class RequestContext(request: HttpRequest, responsePromise: Promise[HttpResponse], retriesLeft: Int) {
    require(retriesLeft >= 0)
  }
  case class ResponseContext(rc: RequestContext, response: Try[HttpResponse])

  private case object ShutdownNow

  class PoolGateway(poolFlow: Flow[RequestContext, ResponseContext, Any],
                    hcps: HostConnectionPoolSetup,
                    whenShuttingDown: Promise[Future[Unit]])(implicit fm: FlowMaterializer)
    extends ActorSubscriber with ActorPublisher[RequestContext] with LogMessages {
    import context.dispatcher

    private[this] var inputBuffer = immutable.Queue.empty[PoolRequest]
    private[this] var activeIdleTimeout: Option[Cancellable] = None
    private[this] val effectiveDefaultHeaders =
      if (!hcps.setup.defaultHeaders.exists(_.isInstanceOf[headers.Host])) {
        import Uri._
        val normalizedPort = normalizePort(hcps.port, httpScheme(securedConnection = hcps.setup.encrypted))
        headers.Host(hcps.host, normalizedPort) :: hcps.setup.defaultHeaders
      } else hcps.setup.defaultHeaders

    // start the pool flow with this actor acting as source as well as sink
    Source(ActorPublisher(self)).via(poolFlow).runWith(Sink(ActorSubscriber[ResponseContext](self)))

    activateIdleTimeoutIfNecessary()

    def requestStrategy = ZeroRequestStrategy

    def receive: Receive = logMessages() {

      /////////////// FROM POOL DOWNSTREAM //////////////

      case Request(_) ⇒ dispatchRequests() // the pool is ready to take on more requests

      case Cancel ⇒
        // somehow the pool shut down, however, we don't do anything here because we'll also see an
        // OnComplete or OnError which we use as the sole trigger for cleaning up
        ()

      /////////////// FROM POOL UPSTREAM //////////////

      case OnNext(ResponseContext(rc, responseTry)) ⇒
        rc.responsePromise.complete(responseTry)
        activateIdleTimeoutIfNecessary()

      case OnComplete ⇒ // the pool shut down
        if (!whenShuttingDown.trySuccess(Future.successful(())))
          whenShuttingDown.future.foreach(_.asInstanceOf[Promise[Unit]].success(()))
        context.stop(self)

      case OnError(e) ⇒ // the pool shut down
        if (!whenShuttingDown.trySuccess(Future.failed(e)))
          whenShuttingDown.future.foreach(_.asInstanceOf[Promise[Unit]].failure(e))
        context.stop(self)

      /////////////// FROM CLIENT //////////////

      case x: PoolRequest ⇒
        activeIdleTimeout foreach { timeout ⇒
          timeout.cancel()
          activeIdleTimeout = None
        }
        if (totalDemand > 0) dispatchRequest(x)
        else inputBuffer = inputBuffer.enqueue(x)
        request(1) // for every incoming request we demand one response from the pool

      case BeginShutdown ⇒
        // signal that we don't want to accept new requests and will shutdown soon
        if (whenShuttingDown.trySuccess(Promise[Unit]().future))
          context.system.scheduler.scheduleOnce(hcps.setup.settings.shutdownGracePeriod, self, ShutdownNow)

      case ShutdownNow ⇒ onComplete() // grace period is over, we can now safely tear down the pool
    }

    @tailrec private def dispatchRequests(): Unit =
      if (totalDemand > 0 && inputBuffer.nonEmpty) {
        dispatchRequest(inputBuffer.head)
        inputBuffer = inputBuffer.tail
        dispatchRequests()
      }

    def dispatchRequest(pr: PoolRequest): Unit = {
      val effectiveRequest = pr.request withDefaultHeaders effectiveDefaultHeaders
      val retries = if (pr.request.method.isIdempotent) hcps.setup.settings.maxRetries else 0
      onNext(RequestContext(effectiveRequest, pr.responsePromise, retries))
    }

    def activateIdleTimeoutIfNecessary(): Unit =
      if (remainingRequested == 0 && hcps.setup.settings.idleTimeout.isFinite) {
        import context.dispatcher
        val timeout = hcps.setup.settings.idleTimeout.asInstanceOf[FiniteDuration]
        activeIdleTimeout = Some(context.system.scheduler.scheduleOnce(timeout, self, BeginShutdown))
      }
  }
}
