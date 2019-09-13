/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.pattern.BackoffSupervisor
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }

import scala.concurrent.duration._
import scala.util.Try

/**
 * INTERNAL API.
 */
@InternalApi private[akka] class RetryFlowCoordinator[InData, UserCtx, OutData](
    retryWith: (InData, Try[OutData], UserCtx) => Option[(InData, UserCtx)],
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    maxRetries: Int)
    extends GraphStage[
      BidiShape[(InData, UserCtx), (InData, UserCtx), (Try[OutData], UserCtx), (Try[OutData], UserCtx)]] {

  private val externalIn = Inlet[(InData, UserCtx)]("RetryFlow.externalIn")
  private val externalOut = Outlet[(Try[OutData], UserCtx)]("RetryFlow.externalOut")

  private val internalOut = Outlet[(InData, UserCtx)]("RetryFlow.internalOut")
  private val internalIn = Inlet[(Try[OutData], UserCtx)]("RetryFlow.internalIn")

  override val shape
      : BidiShape[(InData, UserCtx), (InData, UserCtx), (Try[OutData], UserCtx), (Try[OutData], UserCtx)] =
    BidiShape(externalIn, internalOut, internalIn, externalOut)

  override def createLogic(attributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    private var elementInProgress: Option[(InData, UserCtx)] = None
    private var retryNo = 0

    setHandler(
      externalIn,
      new InHandler {
        override def onPush(): Unit = {
          val element = grab(externalIn)
          elementInProgress = Some(element)
          retryNo = 0
          push(internalOut, element)
        }

        override def onUpstreamFinish(): Unit =
          if (elementInProgress.isEmpty) {
            completeStage()
          }
      })

    setHandler(
      internalOut,
      new OutHandler {
        override def onPull(): Unit = {
          if (elementInProgress.isEmpty) {
            if (!hasBeenPulled(externalIn) && !isClosed(externalIn)) {
              pull(externalIn)
            }
          }
        }

        override def onDownstreamFinish(cause: Throwable): Unit = {
          setKeepGoing(true)
        }
      })

    setHandler(internalIn, new InHandler {
      override def onPush(): Unit = {
        val result = grab(internalIn)
        retryWith(elementInProgress.get._1, result._1, result._2) match {
          case None                             => pushExternal(result)
          case Some(_) if retryNo == maxRetries => pushExternal(result)
          case Some(element)                    => planRetry(element)
        }
      }
    })

    setHandler(externalOut, new OutHandler {
      override def onPull(): Unit =
        // external demand
        if (!hasBeenPulled(internalIn)) pull(internalIn)
    })

    private def pushExternal(result: (Try[OutData], UserCtx)): Unit = {
      elementInProgress = None
      push(externalOut, result)
      if (isClosed(externalIn)) {
        completeStage()
      }
    }

    private def planRetry(element: (InData, UserCtx)): Unit = {
      val delay = BackoffSupervisor.calculateDelay(retryNo, minBackoff, maxBackoff, randomFactor)
      elementInProgress = Some(element)
      retryNo += 1
      pull(internalIn)
      scheduleOnce(element, delay)
    }

    override def onTimer(timerKey: Any): Unit = timerKey match {
      case element: (InData, UserCtx) =>
        push(internalOut, element)
    }

  }
}
