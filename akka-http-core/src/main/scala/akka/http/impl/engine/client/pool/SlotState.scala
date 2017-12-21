/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client.pool

import akka.annotation.InternalApi
import akka.http.impl.engine.client.PoolFlow.RequestContext
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ConnectionPoolSettings

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

/**
 * Internal API
 *
 * Interface between slot states and the actual slot.
 */
@InternalApi
private[pool] abstract class SlotContext {
  def openConnection(): Future[Http.OutgoingConnection]
  def pushRequestToConnectionAndThen(request: HttpRequest, nextState: SlotState): SlotState

  /** Will try to close the current connection. Will do nothing if there is no current connection. */
  def closeConnection(): Unit
  def isConnectionClosed: Boolean

  def dispatchResponseResult(req: RequestContext, result: Try[HttpResponse]): Unit

  def willCloseAfter(res: HttpResponse): Boolean

  def debug(msg: String): Unit
  def debug(msg: String, arg1: AnyRef): Unit
  def debug(msg: String, arg1: AnyRef, arg2: AnyRef): Unit
  def debug(msg: String, arg1: AnyRef, arg2: AnyRef, arg3: AnyRef): Unit

  def warning(msg: String): Unit
  def warning(msg: String, arg1: AnyRef): Unit

  def settings: ConnectionPoolSettings
}

/* Internal API */
@InternalApi
private[pool] sealed abstract class SlotState extends Product {
  def isIdle: Boolean
  def isConnected: Boolean

  def onPreConnect(ctx: SlotContext): SlotState = illegalState(ctx, "preConnect")
  def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = illegalState(ctx, "connected attempt succeeded")
  def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "connection attempt failed")

  def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = illegalState(ctx, "new request")

  /** Will be called either immediately if the request entity is strict or otherwise later */
  def onRequestEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "request entity completed")
  def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "request entity failed")

  def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = illegalState(ctx, "receive response")

  /** Called when the response out port is ready to receive a further response (successful or failed) */
  def onResponseDispatchable(ctx: SlotContext): SlotState = illegalState(ctx, "responseDispatched")

  def onResponseEntitySubscribed(ctx: SlotContext): SlotState = illegalState(ctx, "responseEntitySubscribed")

  /** Will be called either immediately if the response entity is strict or otherwise later */
  def onResponseEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "response entity completed")
  def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "response entity failed")

  def onConnectionCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "connection completed")
  def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "connection failed")

  def onTimeout(ctx: SlotContext): SlotState = illegalState(ctx, "timeout")

  def onShutdown(ctx: SlotContext): Unit = ()

  /** A slot can define a timeout for that state after which onTimeout will be called. */
  def stateTimeout: Duration = Duration.Inf

  protected def illegalState(ctx: SlotContext, what: String): SlotState = {
    ctx.debug(s"Got unexpected event [$what] in state [$name]]")
    throw new IllegalStateException(s"Cannot [$what] when in state [$name]")
  }

  def name: String = productPrefix
}

/**
 * Internal API
 *
 * Implementation of slot logic that is completed decoupled from the machinery bits which are implemented in the GraphStageLogic
 * and exposed only through [[SlotContext]].
 */
@InternalApi
private[pool] object SlotState {
  sealed abstract class ConnectedState extends SlotState {
    def isConnected: Boolean = true

    protected def ignoreAndCloseConnection(ctx: SlotContext, when: String): SlotState = {
      ctx.debug("Closing connection (and staying in state) when [{}]", when)
      ctx.closeConnection()
      this
    }
  }
  sealed trait IdleState extends SlotState {
    final override def isIdle = true
  }
  sealed private[pool] /* to avoid warnings */ trait BusyState extends SlotState {
    final override def isIdle = false // no HTTP pipelining right now
    def ongoingRequest: RequestContext

    override def onShutdown(ctx: SlotContext): Unit = {
      // We would like to dispatch a failure here but responseOut might not be ready (or also already shutting down)
      // so we cannot do more than logging the problem here.

      ctx.warning(s"Ongoing request [{}] was dropped because pool is shutting down", ongoingRequest.request.debugString)

      super.onShutdown(ctx)
    }

    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState =
      // TODO: register failed connection attempt to be able to backoff (see https://github.com/akka/akka-http/issues/1391)
      failOngoingRequest(ctx, "connection attempt failed", cause)

    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = failOngoingRequest(ctx, "request entity stream failed", cause)
    override def onConnectionCompleted(ctx: SlotContext): SlotState =
      // There's no good reason why the connection stream (i.e. the user-facing client Flow[HttpRequest, HttpResponse])
      // would complete during processing of a request.
      // One reason might be that failures on the TCP layer don't necessarily propagate through the stack as failures
      // because of the notorious cancel/failure propagation which can convert failures into completion.
      failOngoingRequest(ctx, "connection completed", new IllegalStateException("Connection was shutdown.") with NoStackTrace)

    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = this failOngoingRequest (ctx, "connection failure", cause)

    private def failOngoingRequest(ctx: SlotContext, signal: String, cause: Throwable): SlotState = {
      ctx.debug("Ongoing request [{}] is failed because of [{}]: [{}]", ongoingRequest.request.debugString, signal, cause.getMessage)
      ctx.closeConnection()
      WaitingForResponseDispatch(ongoingRequest, Failure(cause))
    }
  }

  case object Unconnected extends SlotState with IdleState {
    def isConnected: Boolean = false

    override def onPreConnect(ctx: SlotContext): SlotState = {
      ctx.openConnection()
      PreConnecting
    }

    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = {
      ctx.openConnection()
      Connecting(requestContext)
    }
  }
  case object Idle extends ConnectedState with IdleState with WithRequestDispatching {
    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      dispatchRequestToConnection(ctx, requestContext)

    override def onConnectionCompleted(ctx: SlotContext): SlotState = Unconnected
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = Unconnected

  }
  sealed trait WithRequestDispatching { _: ConnectedState ⇒
    def dispatchRequestToConnection(ctx: SlotContext, ongoingRequest: RequestContext): SlotState = {
      val r = ongoingRequest.request
      ctx.pushRequestToConnectionAndThen(r, WaitingForEndOfRequestEntity(ongoingRequest))
    }
  }

  final case class Connecting(ongoingRequest: RequestContext) extends ConnectedState with BusyState with WithRequestDispatching {
    override def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was established")
      dispatchRequestToConnection(ctx, ongoingRequest)
    }
    // connection failures are handled by BusyState implementations
  }

  case object PreConnecting extends ConnectedState with IdleState with WithRequestDispatching {
    override def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was (pre-)established")
      Idle
    }
    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      Connecting(requestContext)

    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState =
      // TODO: register failed connection attempt to be able to backoff (see https://github.com/akka/akka-http/issues/1391)
      closeAndGoToUnconnected(ctx, "connection attempt failed", cause)
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState =
      closeAndGoToUnconnected(ctx, "connection failed", cause)
    override def onConnectionCompleted(ctx: SlotContext): SlotState =
      closeAndGoToUnconnected(ctx, "connection completed", new IllegalStateException("Unexpected connection closure") with NoStackTrace)

    private def closeAndGoToUnconnected(ctx: SlotContext, signal: String, cause: Throwable): SlotState = {
      ctx.debug("Connection was closed by [{}] while preconnecting because of [{}]", signal, cause.getMessage)
      ctx.closeConnection()
      Unconnected
    }
  }
  final case class WaitingForEndOfRequestEntity(ongoingRequest: RequestContext) extends ConnectedState with BusyState {
    override def onRequestEntityCompleted(ctx: SlotContext): SlotState =
      WaitingForResponse(ongoingRequest)

    // connection failures are handled by BusyState implementations
  }
  final case class WaitingForResponse(ongoingRequest: RequestContext) extends ConnectedState with BusyState {
    override def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState =
      WaitingForResponseDispatch(ongoingRequest, Success(response))

    // connection failures are handled by BusyState implementations
  }
  final case class WaitingForResponseDispatch(
    ongoingRequest: RequestContext,
    result:         Try[HttpResponse]) extends ConnectedState with BusyState {
    /** Called when the response out port is ready to receive a further response (successful or failed) */
    override def onResponseDispatchable(ctx: SlotContext): SlotState = {
      ctx.dispatchResponseResult(ongoingRequest, result)

      result match {
        case Success(res) ⇒ WaitingForResponseEntitySubscription(ongoingRequest, res, ctx.settings.responseEntitySubscriptionTimeout)
        case Failure(cause) ⇒
          ctx.closeConnection()
          Unconnected
      }
    }

    // we already got a result so ignore any subsequent errors
    override def onConnectionCompleted(ctx: SlotContext): SlotState = ignoreAndCloseConnection(ctx, "connection completed")
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = ignoreAndCloseConnection(ctx, "connection failed")
    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = ignoreAndCloseConnection(ctx, "connection attempt failed")
    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = ignoreAndCloseConnection(ctx, "request entity failed")
  }

  private[pool] /* to avoid warnings */ trait BusyWithResultAlreadyDispatched extends ConnectedState with BusyState {
    override def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.debug(s"Response entity for request [{}] failed with [{}]", ongoingRequest.request.debugString, cause.getMessage)
      // response must have already been dispatched, so don't try to dispatch a response
      ctx.closeConnection()
      Unconnected
    }

    // ignore now, we'll clean up later in onResponseEntityCompleted if connection is closed
    override def onConnectionCompleted(ctx: SlotContext): SlotState = this
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = this
  }

  final case class WaitingForResponseEntitySubscription(
    ongoingRequest:  RequestContext,
    ongoingResponse: HttpResponse, override val stateTimeout: Duration) extends ConnectedState with BusyWithResultAlreadyDispatched {

    override def onResponseEntitySubscribed(ctx: SlotContext): SlotState =
      WaitingForEndOfResponseEntity(ongoingRequest, ongoingResponse)

    override def onTimeout(ctx: SlotContext): SlotState = {
      ctx.warning(
        s"Response entity was not subscribed after $stateTimeout. Make sure to read the response entity body or call `discardBytes()` on it. " +
          s"${ongoingRequest.request.debugString} -> ${ongoingResponse.debugString}")
      ctx.closeConnection()
      Unconnected
    }

  }
  final case class WaitingForEndOfResponseEntity(
    ongoingRequest:  RequestContext,
    ongoingResponse: HttpResponse) extends ConnectedState with BusyWithResultAlreadyDispatched {

    override def onResponseEntityCompleted(ctx: SlotContext): SlotState =
      if (ctx.willCloseAfter(ongoingResponse) || ctx.isConnectionClosed) {
        ctx.closeConnection()
        Unconnected
      } else
        Idle
  }
}
