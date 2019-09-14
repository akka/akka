/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.annotation.ApiMayChange
import akka.stream.{ BidiShape, FanInShape2, FlowShape }
import akka.stream.impl.RetryFlowCoordinator

import scala.concurrent.duration._
import scala.util.Try

object RetryFlow {

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` partial function. It takes an output element of the wrapped
   * flow and should return one or more requests to be retried. For example:
   *
   * `case Failure(_) => Some(List(..., ..., ...))` - for every failed response will issue three requests to retry
   *
   * `case Failure(_) => Some(Nil)` - every failed response will be ignored
   *
   * `case Failure(_) => None` - every failed response will be propagated downstream
   *
   * `case Success(_) => Some(List(...))` - for every successful response a single retry will be issued
   *
   * A successful or failed response will be propagated downstream if it is not matched by the `retryFlow` function.
   *
   * If a successful response is matched and issued a retry, the response is still propagated downstream.???
   *
   * The implementation of the RetryFlow assumes that `flow` follows one-in-one-out element semantics.
   *
   * The wrapped `flow` and `retryWith` takes an additional `State` parameter which can be used to correlate a request
   * with a response.
   *
   * Backoff state is tracked separately per-element coming into the wrapped `flow`.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param retryWith retry condition decision partial function
   */
  @ApiMayChange
  def withBackoff[In, Out, State, Mat](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRetries: Int,
      flow: Flow[(In, State), (Try[Out], State), Mat])(
      retryWith: (In, Try[Out], State) => Option[(In, State)]): Flow[(In, State), (Try[Out], State), Mat] =
    withBackoffAndContext(minBackoff, maxBackoff, randomFactor, maxRetries, FlowWithContext.fromTuples(flow))(retryWith).asFlow

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` function. It takes the original emitted element,
   * the response emitted by `flow`, and should return a request to be retried. For example:
   *
   * `case (_, Failure(_), _) => Some(...)` - the request will be tried again
   *
   * `case (_, Failure(_), _) => None` - the failed response will be propagated downstream
   *
   * `case (_, Success(_), _) => Some(...)` - after the successful response the request will be tried again
   *
   * The implementation of the `RetryFlow` assumes that `flow` follows one-in-one-out element semantics.
   *
   * The wrapped `flow` and `retryWith` take an additional `UserContext` parameter which can be just
   * a pass-through or used control retrying with other information.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param maxRetries total number of allowed restarts, when reached the last result will be emitted
   *                   even if unsuccessful
   * @param flow a flow with context to retry elements from
   * @param retryWith retry condition decision partial function
   */
  @ApiMayChange
  def withBackoffAndContext[InData, OutData, UserState, Mat](
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      maxRetries: Int,
      flow: FlowWithContext[InData, UserState, Try[OutData], UserState, Mat])(
      retryWith: (InData, Try[OutData], UserState) => Option[(InData, UserState)])
      : FlowWithContext[InData, UserState, Try[OutData], UserState, Mat] =
    FlowWithContext.fromTuples {
      Flow.fromGraph {
        val retryCoordination = BidiFlow.fromGraph(
          new RetryFlowCoordinator[InData, UserState, OutData](
            retryWith,
            minBackoff,
            maxBackoff,
            randomFactor,
            maxRetries))

        retryCoordination.joinMat(flow)(Keep.right)
      }
    }
}
