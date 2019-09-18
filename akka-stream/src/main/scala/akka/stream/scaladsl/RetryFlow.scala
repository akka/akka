/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.annotation.{ ApiMayChange, InternalApi }
import akka.pattern.BackoffSupervisor
import akka.stream.stage._
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }

import scala.concurrent.duration._

object RetryFlow {

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `decideRetry` function. It takes the originally emitted
   * element with its context, and the response emitted by `flow`, and may return a request to be retried.
   *
   * The implementation of the `RetryFlow` assumes that `flow` follows one-in-one-out element semantics,
   * which is expressed by the [[akka.stream.scaladsl.FlowWithContext FlowWithContext]] type.
   *
   * The wrapped `flow` and `decideRetry` take the additional context parameters which can be a context,
   * or used to control retrying with other information.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param maxRetries total number of allowed retries, when reached the last result will be emitted
   *                   even if unsuccessful
   * @param flow a flow with context to retry elements from
   * @param decideRetry retry condition decision function
   */
  @ApiMayChange
  def withBackoffAndContext[In, CtxIn, Out, CtxOut, Mat](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRetries: Int,
      flow: FlowWithContext[In, CtxIn, Out, CtxOut, Mat])(
      decideRetry: ((In, CtxIn), (Out, CtxOut)) => Option[(In, CtxIn)]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    FlowWithContext.fromTuples {
      val retryCoordination = BidiFlow.fromGraph(
        new RetryFlowCoordinator[In, CtxIn, Out, CtxOut](minBackoff, maxBackoff, randomFactor, maxRetries, decideRetry))

      retryCoordination.joinMat(flow)(Keep.right)
    }
}

/**
 * INTERNAL API.
 */
@InternalApi private[akka] class RetryFlowCoordinator[In, CtxIn, Out, CtxOut](
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    maxRetries: Int,
    decideRetry: ((In, CtxIn), (Out, CtxOut)) => Option[(In, CtxIn)])
    extends GraphStage[BidiShape[(In, CtxIn), (In, CtxIn), (Out, CtxOut), (Out, CtxOut)]] {

  private val externalIn = Inlet[(In, CtxIn)]("RetryFlow.externalIn")
  private val externalOut = Outlet[(Out, CtxOut)]("RetryFlow.externalOut")

  private val internalOut = Outlet[(In, CtxIn)]("RetryFlow.internalOut")
  private val internalIn = Inlet[(Out, CtxOut)]("RetryFlow.internalIn")

  override val shape: BidiShape[(In, CtxIn), (In, CtxIn), (Out, CtxOut), (Out, CtxOut)] =
    BidiShape(externalIn, internalOut, internalIn, externalOut)

  override def createLogic(attributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    private var elementInProgress: Option[(In, CtxIn)] = None
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

    setHandler(
      internalIn,
      new InHandler {
        override def onPush(): Unit = {
          val result = grab(internalIn)
          elementInProgress match {
            case None =>
              fail(externalOut, new IllegalStateException(s"inner flow emitted unexpected element $result"))
            case Some(in) =>
              decideRetry(in, result) match {
                case None                             => pushExternal(result)
                case Some(_) if retryNo == maxRetries => pushExternal(result)
                case Some(element)                    => planRetry(element)
              }
          }
        }
      })

    setHandler(externalOut, new OutHandler {
      override def onPull(): Unit =
        // external demand
        if (!hasBeenPulled(internalIn)) pull(internalIn)
    })

    private def pushExternal(result: (Out, CtxOut)): Unit = {
      elementInProgress = None
      push(externalOut, result)
      if (isClosed(externalIn)) {
        completeStage()
      }
    }

    private def planRetry(element: (In, CtxIn)): Unit = {
      val delay = BackoffSupervisor.calculateDelay(retryNo, minBackoff, maxBackoff, randomFactor)
      elementInProgress = Some(element)
      retryNo += 1
      pull(internalIn)
      scheduleOnce(element, delay)
    }

    override def onTimer(timerKey: Any): Unit = timerKey match {
      case element: (In, CtxIn) =>
        push(internalOut, element)
    }

  }
}
