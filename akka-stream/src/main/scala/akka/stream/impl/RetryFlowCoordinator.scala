/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.pattern.BackoffSupervisor
import akka.stream.impl.RetryFlowCoordinator.{ RetryElement, RetryResult, RetryState, RetryTimer }
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }

import scala.collection.immutable
import scala.collection.immutable.{ Queue, SortedSet }
import scala.concurrent.duration._
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi private[akka] object RetryFlowCoordinator {
  final class RetryState(val numberOfRestarts: Int = 0) {
    override def toString: String = s"RetryState(numberOfRestarts=$numberOfRestarts)"
  }
  final class RetryElement[In, State](val in: In, val userState: State, val internalState: RetryState) {
    override def toString: String = s"RetryElement($internalState)"
  }
  final class RetryResult[In, Out, UserState](
      val tried: RetryElement[In, UserState],
      val out: Try[Out],
      val userCtx: UserState) {
    override def toString: String = s"RetryResult(${out.getClass.getSimpleName})"
  }
  case object RetryTimer
}

/**
 * INTERNAL API.
 *
 * external in -- ----> internal out ... origFlow ... internalIn (retryWith)
 * (InData, UserCtx) |                                               None -> externalOut
 *                  ------------------------------------------- Some(s)
 *
 *
 */
@InternalApi private[akka] class RetryFlowCoordinator[InData, UserCtx, OutData](
    outBufferSize: Int,
    retryWith: (InData, Try[OutData], UserCtx) => Option[(InData, UserCtx)],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double)
    extends GraphStage[BidiShape[
      (InData, UserCtx),
      RetryElement[InData, UserCtx],
      RetryResult[InData, OutData, UserCtx],
      (Try[OutData], UserCtx)]] {

  private type In = (InData, UserCtx)
  private type Out = (Try[OutData], UserCtx)

  private val externalIn = Inlet[(InData, UserCtx)]("RetryFlow.externalIn")
  private val externalOut = Outlet[(Try[OutData], UserCtx)]("RetryFlow.externalOut")

  private val internalOut = Outlet[RetryElement[InData, UserCtx]]("RetryFlow.internalOut")
  private val internalIn = Inlet[RetryResult[InData, OutData, UserCtx]]("RetryFlow.internalIn")

  override val shape: BidiShape[In, RetryElement[InData, UserCtx], RetryResult[InData, OutData, UserCtx], Out] =
    BidiShape(externalIn, internalOut, internalIn, externalOut)

  override def createLogic(attributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    private var elementInProgress = false

    setHandler(
      externalIn,
      new InHandler {
        override def onPush(): Unit = {
          val (in, userState) = grab(externalIn)
          val element: RetryElement[InData, UserCtx] = {
            new RetryElement(in, userState, new RetryState())
          }
          elementInProgress = true
          push(internalOut, element)
        }

        override def onUpstreamFinish(): Unit =
          if (!elementInProgress) {
            completeStage()
          }
      })

    setHandler(
      internalOut,
      new OutHandler {
        override def onPull(): Unit = {
          if (!elementInProgress) {
            if (!hasBeenPulled(externalIn) && !isClosed(externalIn)) {
              pull(externalIn)
            }
          }
        }

        override def onDownstreamFinish(cause: Throwable): Unit = {
          setKeepGoing(true)
        }
      })

    setHandler(
      internalIn,
      new InHandler {
        override def onPush(): Unit = {
          val result = grab(internalIn)
          retryWith(result.tried.in, result.out, result.userCtx) match {
            case None =>
              elementInProgress = false
              push(externalOut, (result.out, result.userCtx))
              if (isClosed(externalIn)) {
                completeStage()
              }
            case Some((inData, userCtx)) =>
              val numRestarts = result.tried.internalState.numberOfRestarts + 1
              val element = new RetryElement(inData, userCtx, new RetryState(numRestarts))
              val delay = BackoffSupervisor.calculateDelay(numRestarts, minBackoff, maxBackoff, randomFactor)
              scheduleOnce(element, delay)

          }
        }
      })

    setHandler(externalOut, new OutHandler {
      override def onPull(): Unit =
        // external demand
        if (!hasBeenPulled(internalIn)) pull(internalIn)
    })

    override def onTimer(timerKey: Any): Unit = timerKey match {
      case element: RetryElement[InData, UserCtx] =>
        pull(internalIn)
        push(internalOut, element)
    }

  }
}
