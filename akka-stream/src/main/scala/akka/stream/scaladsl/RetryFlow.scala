/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.annotation.{ ApiMayChange, InternalApi }
import akka.pattern.BackoffSupervisor
import akka.stream.SubscriptionWithCancelException.NonFailureCancellation
import akka.stream.stage._
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.util.OptionVal

import scala.concurrent.duration._

object RetryFlow {

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `decideRetry` function. It takes the originally emitted
   * element and the response emitted by `flow`, and may return a request to be retried.
   *
   * The implementation of the `RetryFlow` requires that `flow` follows one-in-one-out semantics,
   * the [[akka.stream.scaladsl.Flow Flow]] may not filter elements,
   * nor emit more than one element per incoming element. The `RetryFlow` will fail if two elements are
   * emitted from the `flow`, it will be stuck "forever" if nothing is emitted. Just one element will
   * be emitted into the `flow` at any time. The `flow` needs to emit an element before the next
   * will be emitted to it.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param maxRetries total number of allowed retries, when reached the last result will be emitted
   *                   even if unsuccessful
   * @param flow a flow to retry elements from
   * @param decideRetry retry condition decision function
   */
  @ApiMayChange(issue = "https://github.com/akka/akka/issues/27960")
  def withBackoff[In, Out, Mat](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRetries: Int,
      flow: Flow[In, Out, Mat])(decideRetry: (In, Out) => Option[In]): Flow[In, Out, Mat] =
    Flow.fromGraph {
      val retryCoordination = BidiFlow.fromGraph(
        new RetryFlowCoordinator[In, Out](minBackoff, maxBackoff, randomFactor, maxRetries, decideRetry))
      retryCoordination.joinMat(flow)(Keep.right)
    }

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `decideRetry` function. It takes the originally emitted
   * element with its context, and the response emitted by `flow`, and may return a request to be retried.
   *
   * The implementation of the `RetryFlow` requires that `flow` follows one-in-one-out semantics,
   * the [[akka.stream.scaladsl.FlowWithContext FlowWithContext]] may not filter elements,
   * nor emit more than one element per incoming element. The `RetryFlow` will fail if two elements are
   * emitted from the `flow`, it will be stuck "forever" if nothing is emitted. Just one element will
   * be emitted into the `flow` at any time. The `flow` needs to emit an element before the next
   * will be emitted to it.
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
  @ApiMayChange(issue = "https://github.com/akka/akka/issues/27960")
  def withBackoffAndContext[In, CtxIn, Out, CtxOut, Mat](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRetries: Int,
      flow: FlowWithContext[In, CtxIn, Out, CtxOut, Mat])(
      decideRetry: ((In, CtxIn), (Out, CtxOut)) => Option[(In, CtxIn)]): FlowWithContext[In, CtxIn, Out, CtxOut, Mat] =
    FlowWithContext.fromTuples {
      val retryCoordination = BidiFlow.fromGraph(
        new RetryFlowCoordinator[(In, CtxIn), (Out, CtxOut)](
          minBackoff,
          maxBackoff,
          randomFactor,
          maxRetries,
          decideRetry))

      retryCoordination.joinMat(flow)(Keep.right)
    }

}

/**
 * INTERNAL API.
 *
 * ```
 *        externalIn
 *            |
 *            |
 *   +-> internalOut -->+
 *   |                  |
 *   |                 flow
 *   |                  |
 *   |     internalIn --+
 *   +<-yes- retry?
 *            |
 *            no
 *            |
 *       externalOut
 * ```
 */
@InternalApi private[akka] final class RetryFlowCoordinator[In, Out](
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    maxRetries: Int,
    decideRetry: (In, Out) => Option[In])
    extends GraphStage[BidiShape[In, In, Out, Out]] {

  private val externalIn = Inlet[In]("RetryFlow.externalIn")
  private val externalOut = Outlet[Out]("RetryFlow.externalOut")

  private val internalOut = Outlet[In]("RetryFlow.internalOut")
  private val internalIn = Inlet[Out]("RetryFlow.internalIn")

  override val shape: BidiShape[In, In, Out, Out] =
    BidiShape(externalIn, internalOut, internalIn, externalOut)

  override def createLogic(attributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {

    private var elementInProgress: OptionVal[In] = OptionVal.none
    private var retryNo = 0

    setHandler(
      externalIn,
      new InHandler {
        override def onPush(): Unit = {
          val element = grab(externalIn)
          elementInProgress = OptionVal.Some(element)
          retryNo = 0
          pushInternal(element)
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
          if (elementInProgress.isEmpty || !cause.isInstanceOf[NonFailureCancellation]) {
            super.onDownstreamFinish(cause)
          } else {
            // emit elements before finishing
            setKeepGoing(true)
          }
        }
      })

    setHandler(
      internalIn,
      new InHandler {
        override def onPush(): Unit = {
          val result = grab(internalIn)
          elementInProgress match {
            case OptionVal.None =>
              failStage(
                new IllegalStateException(
                  s"inner flow emitted unexpected element $result; the flow must be one-in one-out"))
            case OptionVal.Some((_, _)) if retryNo == maxRetries => pushExternal(result)
            case OptionVal.Some(in) =>
              decideRetry(in, result) match {
                case None          => pushExternal(result)
                case Some(element) => planRetry(element)
              }
          }
        }
      })

    setHandler(externalOut, new OutHandler {
      override def onPull(): Unit =
        // external demand
        if (!hasBeenPulled(internalIn)) pull(internalIn)
    })

    private def pushInternal(element: In): Unit = {
      push(internalOut, element)
    }

    private def pushExternal(result: Out): Unit = {
      elementInProgress = OptionVal.none
      push(externalOut, result)
      if (isClosed(externalIn)) {
        completeStage()
      } else if (isAvailable(internalOut)) {
        pull(externalIn)
      }
    }

    private def planRetry(element: In): Unit = {
      val delay = BackoffSupervisor.calculateDelay(retryNo, minBackoff, maxBackoff, randomFactor)
      elementInProgress = OptionVal.Some(element)
      retryNo += 1
      pull(internalIn)
      scheduleOnce(element, delay)
    }

    override def onTimer(timerKey: Any): Unit = pushInternal(timerKey.asInstanceOf[In])

  }
}
