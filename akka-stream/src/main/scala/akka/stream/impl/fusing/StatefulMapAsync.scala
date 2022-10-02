/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet, StreamDetachedException, Supervision }
import akka.stream.Supervision.Decider
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.impl.{ Buffer => BufferImpl }
import akka.util.OptionVal

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] final class StatefulMapAsync[S, In, Out](parallelism: Int)(
    attributes: Attributes,
    create: () => Future[S],
    //TODO (S, In) => (S, Future[Out]) seems more comfortable for user
    f: (S, In) => Future[(S, Out)],
    onComplete: S => Future[Option[Out]],
    combineState: (S, S) => S)
    extends GraphStage[FlowShape[In, Out]] {

  import MapAsync._

  private val in = Inlet[In]("StatefulMapAsync.in")
  private val out = Outlet[Out]("StatefulMapAsync.out")

  override def initialAttributes = attributes

  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      setHandlers(in, out, this)

      lazy val decider: Decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      // if release hasn't been called
      private var stateAcquired: Boolean = true
      // if it is need to push element after state initialized
      // we need this flag bcs if stream ever been pushed or pulled at the time state doesn't available
      // that will require triggering push in create callback, but prevent push been triggered by just preStart
      private var tryPushAfterInitialized: Boolean = false
      private var state: OptionVal[S] = OptionVal.none[S]
      private var buffer: BufferImpl[Holder[(S, Out)]] = _
      // single size buffer to reserve element that arrival when state isn't available
      private var earlyPulledElem: OptionVal[In] = OptionVal.none[In]

      private val initCB =
        getAsyncCallback[Try[S]] {
          case Failure(e) => failStage(e)
          case Success(s) =>
            state = OptionVal.Some(s)
            if (tryPushAfterInitialized) pushNextIfPossible()
        }.invokeWithFeedback _

      private val futureCB =
        getAsyncCallback[Holder[(S, Out)]](holder =>
          holder.elem match {
            case Success(_) => pushNextIfPossible()
            case Failure(ex) =>
              holder.supervisionDirectiveFor(decider, ex) match {
                // fail fast as if supervision says so
                case Supervision.Stop    => releaseThenFailStage(ex)
                case Supervision.Restart => resetState()
                case Supervision.Resume  => pushNextIfPossible()
              }
          })

      private def failOrCompleteStage(exOpt: Option[Throwable]): Unit = exOpt match {
        case Some(ex) => failStage(ex)
        case None     => completeStage()
      }

      private def emitThenFailOrCompleteStage(ex: Option[Throwable], result: Try[Option[Out]]): Unit = result match {
        case Success(elemOpt) =>
          elemOpt match {
            case Some(elem) => emit(out, elem, () => failOrCompleteStage(ex))
            case None       => failOrCompleteStage(ex)
          }
        case Failure(releaseEx) => failStage(releaseEx)
      }

      private val releaseCB =
        getAsyncCallback[(Option[Throwable], Try[Option[Out]])]((emitThenFailOrCompleteStage _).tupled).invoke _

      private def releaseThenFailStage(ex: Throwable): Unit = {
        if (stateAcquired) {
          state.foreach(s => {
            try {
              val future = onComplete(s)
              stateAcquired = false
              future.value match {
                case None =>
                  future.onComplete(v => releaseCB(Some(ex) -> v))(akka.dispatch.ExecutionContexts.parasitic)
                case Some(v) =>
                  emitThenFailOrCompleteStage(Some(ex), v)
              }
            } catch {
              // ideally should never throw when release resource
              case NonFatal(e) => failStage(e)
            }
          })
        }
      }

      private def releaseThenCompleteStage(): Unit = {
        if (stateAcquired) {
          state.foreach(s => {
            try {
              val future = onComplete(s)
              stateAcquired = false
              future.value match {
                case None =>
                  future.onComplete(getAsyncCallback[Try[Option[Out]]] {
                    case Failure(e) => failStage(e)
                    case Success(output) =>
                      output match {
                        case Some(v) => emit(out, v, () => initState())
                        case None    => initState()
                      }
                  }.invoke)(akka.dispatch.ExecutionContexts.parasitic)
                case Some(v) =>
                  emitThenFailOrCompleteStage(None, v)
              }
            } catch {
              // ideally should never throw when release resource
              case NonFatal(e) => failStage(e)
            }
          })
        }
      }

      override def onUpstreamFinish(): Unit =
        if (buffer.isEmpty && earlyPulledElem.isEmpty) {
          releaseThenCompleteStage()
        }

      override def onUpstreamFailure(ex: Throwable): Unit = if (state.isDefined) {
        releaseThenFailStage(ex)
      }

      override def onDownstreamFinish(cause: Throwable): Unit = if (state.isDefined) {
        releaseThenFailStage(cause)
      }

      override def postStop(): Unit = {
        state match {
          case OptionVal.Some(value) =>
            // outlet isn't available here
            if (stateAcquired) onComplete(value)
          //TODO figure out what should be done here
          // since resource maybe in the half way of acquisition
          case _ =>
        }
      }

      override def preStart(): Unit = {
        initState()
        buffer = BufferImpl(parallelism, inheritedAttributes)
      }

      override def onPull(): Unit =
        state match {
          case OptionVal.Some(_) => pushNextIfPossible()
          case _    => tryPushAfterInitialized = true
        }

      override def onPush(): Unit = {
        val elem = grab(in)
        state match {
          case OptionVal.Some(_) =>
            applyUserFunction(elem)
            pullIfNeeded()
          case _ =>
            earlyPulledElem = OptionVal.Some(elem)
            tryPushAfterInitialized = true
        }
      }

      @tailrec
      private def pushNextIfPossible(): Unit =
        if (earlyPulledElem.isDefined) applyUserFunction(earlyPulledElem.get)
        else if (buffer.isEmpty) pullIfNeeded()
        else if (buffer.peek().elem eq NotYetThere) pullIfNeeded() // ahead of line blocking to keep order
        else if (isAvailable(out)) {
          val holder = buffer.dequeue()
          holder.elem match {
            case Success(elem) =>
              if (elem != null && elem._1 != null && elem._2 != null) {
                // state has to be presented here
                state = OptionVal.Some(combineState(state.get, elem._1))
                push(out, elem._2)
                pullIfNeeded()
              } else {
                // elem is null
                pullIfNeeded()
                pushNextIfPossible()
              }

            case Failure(NonFatal(ex)) =>
              holder.supervisionDirectiveFor(decider, ex) match {
                // this could happen if we are looping in pushNextIfPossible and end up on a failed future before the
                // onComplete callback has run
                case Supervision.Stop => failStage(ex)
                case _                =>
                  // try next element
                  pushNextIfPossible()
              }
            case Failure(ex) =>
              // fatal exception in buffer, not sure that it can actually happen, but for good measure
              throw ex
          }
        }

      private def pullIfNeeded(): Unit = {
        // this will only be called after state future was completed
        // so no need to check if state future is still pending or first element buffer is still full
        if (isClosed(in) && buffer.isEmpty) releaseThenCompleteStage()
        else if (earlyPulledElem.isEmpty && buffer.used < parallelism && !hasBeenPulled(in)) tryPull(in)
        // else already pulled and waiting for next element
      }

      def initState(): Unit = {
        val future = create()
        stateAcquired = true
        future.onComplete { s =>
          initCB(s).failed.foreach {
            case _: StreamDetachedException =>
              // stream stopped before created callback could be invoked, we need
              // to close the resource if it is was opened, to not leak it
              s match {
                case Success(r) =>
                  // there is no way that out is still available
                  onComplete(r)
                // it's ok to not set the stateAcquired flag here
                // since stream already been tear down anyway
                case Failure(ex) =>
                  throw ex // failed to open but stream is stopped already
              }
            case _ => // we don't care here
          }(materializer.executionContext)
        }(akka.dispatch.ExecutionContexts.parasitic)
      }

      private def resetState(): Unit = {
        tryPushAfterInitialized = false
        state match {
          case OptionVal.Some(s) =>
            val future = onComplete(s)
            stateAcquired = false
            future.value match {
              case Some(v) => emitThenFailOrCompleteStage(None, v); initState()
              case None    => future.onComplete(v => releaseCB(None -> v))(akka.dispatch.ExecutionContexts.parasitic)
            }
          // unlikely to happen, but good for measurement anyway
          case _ => initState()
        }
      }

      private def applyUserFunction(elem: In): Unit = {
        try {
          val future = f(state.get, elem)
          val holder = new Holder[(S, Out)](NotYetThere, futureCB)
          buffer.enqueue(holder)
          future.value match {
            case None =>
              future.onComplete(holder)(akka.dispatch.ExecutionContexts.parasitic)
            case Some(v) =>
              // #20217 the future is already here, optimization: avoid scheduling it on the dispatcher and
              // run the logic directly on this thread
              holder.setElem(v)
              v match {
                // this optimization also requires us to stop the stage to fail fast if the decider says so:
                case Failure(ex) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop =>
                  failStage(ex)
                case _ => pushNextIfPossible()
              }
          }
        } catch {
          // this logic must only be executed if f throws, not if the future is failed
          case NonFatal(ex) => if (decider(ex) == Supervision.Stop) failStage(ex)
        }
        pullIfNeeded()
      }

    }
}
