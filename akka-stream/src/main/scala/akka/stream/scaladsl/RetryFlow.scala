/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.annotation.ApiMayChange
import akka.stream.impl.RetryFlowCoordinator

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
