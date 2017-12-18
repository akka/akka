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

/**
 * Internal API
 *
 * Interface between slot states and the actual slot.
 */
@InternalApi
private[pool] abstract class SlotContext {
  def openConnection(): Future[Http.OutgoingConnection]
  def pushRequestToConnectionAndThen(request: HttpRequest, nextState: SlotState): SlotState
  def closeConnection(): Unit
  def isConnectionClosed: Boolean

  def dispatchFailure(req: RequestContext, cause: Throwable): Unit
  def dispatchResponse(req: RequestContext, res: HttpResponse): Unit

  def willCloseAfter(res: HttpResponse): Boolean

  def debug(msg: String): Unit
  def debug(msg: String, arg1: AnyRef): Unit

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
  def onConnectedAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = illegalState(ctx, "connected attempt succeeded")
  def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "connection attempt failed")

  def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = illegalState(ctx, "new request")

  /** Will be called either immediately if the request entity is strict or otherwise later */
  def onRequestEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "request entity completed")
  def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "request entity failed")

  def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = illegalState(ctx, "receive response")
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
  }
  sealed trait IdleState extends SlotState {
    final override def isIdle = true
  }
  sealed trait BusyState extends SlotState {
    final override def isIdle = false // no HTTP pipelining right now
    def ongoingRequest: RequestContext

    override def onShutdown(ctx: SlotContext): Unit = {
      ctx.dispatchFailure(
        ongoingRequest,
        new IllegalStateException(s"Slot shut down with ongoing request [${ongoingRequest.request.debugString}]"))
      super.onShutdown(ctx)
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
    override def onConnectedAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was established")
      dispatchRequestToConnection(ctx, ongoingRequest)
    }
    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.debug("Connection attempt failed.")
      // FIXME: register failed connection attempt, schedule request for rerun, backoff new connection attempts
      ctx.dispatchFailure(ongoingRequest, cause)
      Unconnected
    }
  }

  case object PreConnecting extends ConnectedState with IdleState with WithRequestDispatching {
    override def onConnectedAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was (pre-)established")
      Idle
    }
    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.debug("Connection attempt failed.")
      // FIXME: register failed connection attempt, schedule request for rerun, backoff new connection attempts
      Unconnected
    }

    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      Connecting(requestContext)
  }
  final case class WaitingForEndOfRequestEntity(ongoingRequest: RequestContext) extends ConnectedState with BusyState {
    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = WaitingForResponse(ongoingRequest)

    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.dispatchFailure(ongoingRequest, cause)
      ctx.closeConnection()

      Unconnected
    }

  }
  final case class WaitingForResponse(ongoingRequest: RequestContext) extends ConnectedState with BusyState {
    override def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = {
      ctx.dispatchResponse(ongoingRequest, response)

      WaitingForResponseEntitySubscription(ongoingRequest, response, ctx.settings.responseEntitySubscriptionTimeout)
    }

    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.dispatchFailure(ongoingRequest, cause)
      ctx.closeConnection()

      Unconnected
    }
  }
  final case class WaitingForResponseEntitySubscription(
    ongoingRequest:  RequestContext,
    ongoingResponse: HttpResponse, override val stateTimeout: Duration) extends ConnectedState with BusyState {

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
    ongoingResponse: HttpResponse) extends ConnectedState with BusyState {

    override def onResponseEntityCompleted(ctx: SlotContext): SlotState =
      if (ctx.willCloseAfter(ongoingResponse) || ctx.isConnectionClosed) {
        ctx.closeConnection()
        Unconnected
      } else
        Idle

    override def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.debug("Response entity failed with {}", cause)
      // we cannot fail the response at this point, the response has already been dispatched
      ctx.closeConnection()
      Unconnected
    }

    // we ignore these signals here and expect that it will also be flagged on the entity stream
    // FIXME: should we still add timeouts for these cases?
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = this
    override def onConnectionCompleted(ctx: SlotContext): SlotState = this
  }

}
