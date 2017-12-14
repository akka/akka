/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client.pool

import java.util

import akka.NotUsed
import akka.actor.Cancellable
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolFlow.{ RequestContext, ResponseContext }
import akka.http.impl.engine.client.pool.SlotState.Unconnected
import akka.http.impl.util.{ StageLoggingWithOverride, StreamUtils }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse, headers }
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream._
import akka.util.OptionVal

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

/**
 * Internal API
 *
 * New host connection pool implementation.
 *
 * Backpressure logic of the external interface:
 *
 *  * pool pulls if there's a free slot
 *  * pool buffers responses until they are pulled. The buffer is unlimited in theory with the reasoning that
 *    reasonable behavior can be expected from downstream consumers, i.e. at least one pull for every request sent in.
 *
 *    It's hard to say if that's reasonable enough. As we can only ever receive a single pull we will always need a
 *    buffer of at least `max-connections` elements to allow for any parallelism. So, an alternative strategy could be
 *    to leave a response in its slot until it is fetched.
 *
 *    (The old implementation may or may not have implemented similar behavior. It probably had a buffer because of
 *     the merge in the involved graph structure.)
 *
 * The implementation is split up into this class which does all the stream-based wiring. It contains a vector of
 * slots that contain the mutable slot state for every slot.
 *
 * The actual state machine logic is handled in separate [[SlotState]] subclasses that interface with the logic through
 * the clean [[SlotContext]] interface.
 */
@InternalApi
private[client] object NewHostConnectionPool {
  def apply(
    connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
    settings:       ConnectionPoolSettings, log: LoggingAdapter): Flow[RequestContext, ResponseContext, NotUsed] =
    Flow.fromGraph(new HostConnectionPoolStage(connectionFlow, settings, log))

  private final class HostConnectionPoolStage(
    connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]],
    _settings:      ConnectionPoolSettings, _log: LoggingAdapter
  ) extends GraphStage[FlowShape[RequestContext, ResponseContext]] {
    val requestsIn = Inlet[RequestContext]("HostConnectionPoolStage.requestsIn")
    val responsesOut = Outlet[ResponseContext]("HostConnectionPoolStage.responsesOut")

    override val shape = FlowShape(requestsIn, responsesOut)
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLoggingWithOverride with InHandler with OutHandler { logic ⇒
        override def logOverride: LoggingAdapter = _log

        setHandlers(requestsIn, responsesOut, this)

        private[this] var lastTimeoutId = 0L

        val slots = Vector.tabulate(_settings.maxConnections)(new Slot(_))
        val outBuffer: util.Deque[ResponseContext] = new util.ArrayDeque[ResponseContext]
        val retryBuffer: util.Deque[RequestContext] = new util.ArrayDeque[RequestContext]

        override def preStart(): Unit = {
          pull(requestsIn)
          slots.foreach(_.initialize())
        }

        def onPush(): Unit = {
          dispatchRequest(grab(requestsIn))
          pullIfNeeded()
        }
        def onPull(): Unit =
          if (!outBuffer.isEmpty)
            push(responsesOut, outBuffer.pollFirst())

        def manageState(): Unit = {
          pullIfNeeded()
        }

        def pullIfNeeded(): Unit =
          if (hasIdleSlots)
            if (!retryBuffer.isEmpty) {
              log.debug("Dispatching request from retryBuffer")
              dispatchRequest(retryBuffer.pollFirst())
            } else if (!hasBeenPulled(requestsIn))
              pull(requestsIn)

        def hasIdleSlots: Boolean =
          // TODO: optimize by keeping track of idle connections?
          slots.exists(_.isIdle)

        def dispatchResponse(req: RequestContext, res: HttpResponse): Unit =
          dispatchResponseContext(ResponseContext(req, Success(res)))

        def dispatchFailure(req: RequestContext, cause: Throwable): Unit = {
          if (req.retriesLeft > 0) {
            log.debug("Request has {} retries left, retrying...", req.retriesLeft)
            retryBuffer.addLast(req.copy(retriesLeft = req.retriesLeft - 1))
          } else
            dispatchResponseContext(ResponseContext(req, Failure(cause)))
        }

        def dispatchResponseContext(resCtx: ResponseContext): Unit =
          if (outBuffer.isEmpty && isAvailable(responsesOut))
            push(responsesOut, resCtx)
          else
            outBuffer.addLast(resCtx)

        def dispatchRequest(req: RequestContext): Unit = {
          val slot =
            slots.find(_.isIdle)
              .getOrElse(throw new IllegalStateException("Tried to dispatch request when no slot is idle"))

          slot.debug("Dispatching request") // FIXME: add abbreviation
          slot.dispatchRequest(req)
        }

        def numConnectedSlots: Int = slots.count(_.isConnected)

        private class Slot(val slotId: Int) extends SlotContext {
          private[this] var state: SlotState = SlotState.Unconnected
          private[this] var currentTimeoutId: Long = -1
          private[this] var currentTimeout: Cancellable = _

          private[this] var connection: SlotConnection = _
          def isIdle: Boolean = state.isIdle
          def isConnected: Boolean = state.isConnected
          def shutdown(): Unit = {
            // TODO: should we offer errors to the connection?
            closeConnection()

            state.onShutdown(this)
          }

          def initialize(): Unit =
            if (slotId < settings.minConnections) {
              debug("Preconnecting")
              updateState(_.onPreConnect(this))
            }

          def onConnected(outgoing: Http.OutgoingConnection): Unit =
            updateState(_.onConnectedAttemptSucceeded(this, outgoing))

          def onConnectFailed(cause: Throwable): Unit =
            updateState(_.onConnectionAttemptFailed(this, cause))

          def dispatchRequest(req: RequestContext): Unit =
            updateState(_.onNewRequest(this, req))

          def onRequestEntityCompleted(): Unit =
            updateState(_.onRequestEntityCompleted(this))
          def onRequestEntityFailed(cause: Throwable): Unit =
            updateState(_.onRequestEntityFailed(this, cause))

          def onResponseReceived(response: HttpResponse): Unit =
            updateState(_.onResponseReceived(this, response))
          def onResponseEntitySubscribed(): Unit =
            updateState(_.onResponseEntitySubscribed(this))
          def onResponseEntityCompleted(): Unit =
            updateState(_.onResponseEntityCompleted(this))
          def onResponseEntityFailed(cause: Throwable): Unit =
            updateState(_.onResponseEntityFailed(this, cause))

          def onConnectionCompleted(): Unit = updateState(_.onConnectionCompleted(this))
          def onConnectionFailed(cause: Throwable): Unit = updateState(_.onConnectionFailed(this, cause))

          type StateTransition = SlotState ⇒ SlotState
          protected def updateState(f: StateTransition): Unit = {
            def runOneTransition(f: StateTransition): OptionVal[StateTransition] =
              try {
                cancelCurrentTimeout()

                val previousState = state
                state = f(state)
                debug(s"State change [${previousState.name}] -> [${state.name}]")

                state.stateTimeout match {
                  case Duration.Inf ⇒
                  case d: FiniteDuration ⇒
                    val myTimeoutId = createNewTimeoutId()
                    currentTimeoutId = myTimeoutId
                    currentTimeout =
                      materializer.scheduleOnce(d, safeRunnable {
                        if (myTimeoutId == currentTimeoutId) { // timeout may race with state changes, ignore if timeout isn't current any more
                          debug(s"Slot timeout after $d")
                          updateState(_.onTimeout(this))
                        }
                      })
                }

                if (!previousState.isIdle && state.isIdle) {
                  debug("Slot became idle... Trying to pull")
                  pullIfNeeded()
                }

                if (state == Unconnected && numConnectedSlots < settings.minConnections) {
                  debug(s"Preconnecting because number of connected slots fell down to $numConnectedSlots")
                  OptionVal.Some(_.onPreConnect(this))
                } else
                  OptionVal.None

                // put additional bookkeeping here (like keeping track of idle connections)
              } catch {
                case NonFatal(ex) ⇒
                  error(
                    ex,
                    "Slot execution failed. That's probably a bug. Please file a bug at https://github.com/akka/akka-http/issues. Slot is restarted.")

                  try {
                    cancelCurrentTimeout()
                    closeConnection()
                    state.onShutdown(this)
                    OptionVal.None
                  } catch {
                    case NonFatal(ex) ⇒
                      error(ex, "Shutting down slot after error failed.")
                  }
                  state = Unconnected
                  OptionVal.Some(_.onPreConnect(this))
              }

            /** Run a loop of state transitions */
            @tailrec def loop(f: StateTransition, remainingIterations: Int): Unit =
              if (remainingIterations > 0)
                runOneTransition(f) match {
                  case OptionVal.None       ⇒ // no more changes
                  case OptionVal.Some(next) ⇒ loop(next, remainingIterations - 1)
                }
              else
                throw new IllegalStateException(
                  "State transition loop exceeded maximum number of loops. The pool will shutdown itself. " +
                    "That's probably a bug. Please file a bug at https://github.com/akka/akka-http/issues. ")

            loop(f, 10)
          }

          protected def setState(newState: SlotState): Unit =
            updateState(_ ⇒ newState)

          def debug(msg: String): Unit =
            log.debug("[{} ({})] {}", slotId, state.productPrefix, msg)

          def debug(msg: String, arg1: AnyRef): Unit =
            log.debug(s"[{} ({})] $msg", slotId, state.productPrefix, arg1)

          def warning(msg: String): Unit =
            log.warning("[{} ({})] {}", slotId, state.productPrefix, msg)

          def warning(msg: String, arg1: AnyRef): Unit =
            log.warning(s"[{} ({})] $msg", slotId, state.productPrefix, arg1)

          def error(cause: Throwable, msg: String): Unit =
            log.error(cause, s"[{} ({})] $msg", slotId, state.productPrefix)

          def settings: ConnectionPoolSettings = _settings

          def openConnection(): Future[Http.OutgoingConnection] = {
            if (connection ne null) throw new IllegalStateException("Cannot open connection when slot still has an open connection")

            connection = logic.openConnection(this)
            connection.outgoingConnection
          }
          def pushRequestToConnectionAndThen(request: HttpRequest, nextState: SlotState): SlotState = {
            if (connection eq null) throw new IllegalStateException("Cannot open push request to connection when there's no connection")

            // bit of a HACK to make sure onRequestEntityCompleted will end up in the right place
            state = nextState

            connection.pushRequest(request)
            state
          }
          def closeConnection(): Unit =
            if (connection ne null) {
              connection.close()
              connection = null
            }
          def isCurrentConnection(conn: SlotConnection): Boolean = connection eq conn
          def isConnectionClosed: Boolean = (connection eq null) || connection.isClosed

          def dispatchResponse(req: RequestContext, res: HttpResponse): Unit = logic.dispatchResponse(req, res)
          def dispatchFailure(req: RequestContext, cause: Throwable): Unit = logic.dispatchFailure(req, cause)
          def willCloseAfter(res: HttpResponse): Boolean = logic.willClose(res)

          private[this] def cancelCurrentTimeout(): Unit =
            if (currentTimeout ne null) {
              currentTimeout.cancel()
              currentTimeout = null
              currentTimeoutId = -1
            }
        }
        final class SlotConnection(
          _slot:                  Slot,
          requestOut:             SubSourceOutlet[HttpRequest],
          responseIn:             SubSinkInlet[HttpResponse],
          val outgoingConnection: Future[Http.OutgoingConnection]
        ) extends InHandler with OutHandler { connection ⇒
          var ongoingResponseEntity: Option[HttpEntity] = None

          /** Will only be executed if this connection is still the current connection for its slot */
          def withSlot(f: Slot ⇒ Unit): Unit =
            if (_slot.isCurrentConnection(this)) f(_slot)

          def pushRequest(request: HttpRequest): Unit = {
            val newRequest =
              request.entity match {
                case _: HttpEntity.Strict ⇒
                  withSlot(_.onRequestEntityCompleted())
                  request
                case e ⇒
                  val (newEntity, entityComplete) = HttpEntity.captureTermination(request.entity)
                  entityComplete.onComplete(safely {
                    case Success(_)     ⇒ withSlot(_.onRequestEntityCompleted())
                    case Failure(cause) ⇒ withSlot(_.onRequestEntityFailed(cause))
                  })(ExecutionContexts.sameThreadExecutionContext)

                  request.withEntity(newEntity)
              }

            emitRequest(newRequest)
          }
          def close(): Unit = {
            requestOut.complete()
            responseIn.cancel()

            // FIXME: or should we use discardEntity which does Sink.ignore?
            ongoingResponseEntity.foreach(_.dataBytes.runWith(Sink.cancelled)(subFusingMaterializer))
          }
          def isClosed: Boolean = requestOut.isClosed || responseIn.isClosed

          def onPush(): Unit = {
            val response = responseIn.grab()

            withSlot(_.debug("Received response")) // FIXME: add abbreviated info

            response.entity match {
              case _: HttpEntity.Strict ⇒
                withSlot(_.onResponseReceived(response))
                withSlot(_.onResponseEntitySubscribed())
                withSlot(_.onResponseEntityCompleted())
              case e ⇒
                ongoingResponseEntity = Some(e)

                val (newEntity, (entitySubscribed, entityComplete)) =
                  StreamUtils.transformEntityStream(response.entity, StreamUtils.CaptureMaterializationAndTerminationOp)

                entitySubscribed.onComplete(safely {
                  case Success(()) ⇒
                    withSlot(_.onResponseEntitySubscribed())

                    entityComplete.onComplete(safely {
                      case Success(_)     ⇒ withSlot(_.onResponseEntityCompleted())
                      case Failure(cause) ⇒ withSlot(_.onResponseEntityFailed(cause))
                    })(ExecutionContexts.sameThreadExecutionContext)
                })(ExecutionContexts.sameThreadExecutionContext)

                withSlot(_.onResponseReceived(response.withEntity(newEntity)))
            }

            if (!responseIn.isClosed) responseIn.pull()
          }

          override def onUpstreamFinish(): Unit =
            withSlot { slot ⇒
              slot.debug("Connection completed")
              slot.onConnectionCompleted()
            }
          override def onUpstreamFailure(ex: Throwable): Unit =
            withSlot { slot ⇒
              slot.debug("Connection failed")
              slot.onConnectionFailed(ex)
            }

          def onPull(): Unit = () // emitRequests makes sure not to push too early

          override def onDownstreamFinish(): Unit =
            withSlot(_.debug("Connection cancelled"))

          /** Helper that makes sure requestOut is pulled before pushing */
          private def emitRequest(request: HttpRequest): Unit =
            if (requestOut.isAvailable) requestOut.push(request)
            else
              requestOut.setHandler(new OutHandler {
                def onPull(): Unit = {
                  requestOut.push(request)
                  // Implicit assumption is that `connection` was the previous handler. We would just use the
                  // previous handler if there was a way to get at it... SubSourceOutlet.getHandler seems to be missing.
                  requestOut.setHandler(connection)
                }

                override def onDownstreamFinish(): Unit = connection.onDownstreamFinish()
              })
        }
        def openConnection(slot: Slot): SlotConnection = {
          val requestOut = new SubSourceOutlet[HttpRequest](s"PoolSlot[${slot.slotId}].requestOut")

          val responseIn = new SubSinkInlet[HttpResponse](s"PoolSlot[${slot.slotId}].responseIn")
          responseIn.pull()

          slot.debug("Establishing connection")
          val connection =
            Source.fromGraph(requestOut.source)
              .viaMat(connectionFlow)(Keep.right)
              .toMat(responseIn.sink)(Keep.left)
              .run()(subFusingMaterializer)

          connection.onComplete(safely {
            case Success(outgoingConnection) ⇒ slot.onConnected(outgoingConnection)
            case Failure(cause)              ⇒ slot.onConnectFailed(cause)
          })(ExecutionContexts.sameThreadExecutionContext)

          val slotCon = new SlotConnection(slot, requestOut, responseIn, connection)
          requestOut.setHandler(slotCon)
          responseIn.setHandler(slotCon)
          slotCon
        }

        override def onUpstreamFinish(): Unit = {
          log.debug("Pool upstream was completed")
          super.onDownstreamFinish()
        }
        override def onUpstreamFailure(ex: Throwable): Unit = {
          log.debug("Pool upstream failed with {}", ex)
          super.onUpstreamFailure(ex)
        }
        override def onDownstreamFinish(): Unit = {
          log.debug("Pool downstream cancelled")
          super.onDownstreamFinish()
        }
        override def postStop(): Unit = {
          log.debug("Pool stopped")
          slots.foreach(_.shutdown())
        }

        private def willClose(response: HttpResponse): Boolean =
          response.header[headers.Connection].exists(_.hasClose)

        private val safeCallback = getAsyncCallback[() ⇒ Unit](f ⇒ f())
        private def safely[T, U](f: T ⇒ Unit): T ⇒ Unit = t ⇒ safeCallback.invoke(() ⇒ f(t))
        private def safeRunnable(body: ⇒ Unit): Runnable =
          new Runnable {
            def run(): Unit = safeCallback.invoke(() ⇒ body)
          }
        private def createNewTimeoutId(): Long = {
          lastTimeoutId += 1
          lastTimeoutId
        }
      }
  }
}
