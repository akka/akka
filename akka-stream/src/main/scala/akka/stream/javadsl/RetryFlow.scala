/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util.Optional
import java.util.function.BiFunction

import akka.annotation.ApiMayChange
import akka.japi.Pair
import akka.stream.scaladsl

import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }

object RetryFlow {

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` bi-function. On a successful response, this function is
   * passed the response as the first argument. On a failed response, the exception is passed as the second argument.
   * The other respective argument is passed a null. `retryWith` should return one or more requests to be retried.
   *
   * If a successful response is issued a retry, the response is still propagated downstream.
   *
   * The implementation of the RetryFlow assumes that `flow` follows one-in-one-out element semantics.
   *
   * The `flow` and `retryWith` take an additional `State` parameter which can be used to correlate a request
   * with a response.
   *
   * Backoff state is tracked separately per-element coming into the wrapped `flow`.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param retryWith retry condition decision function
   */
  @ApiMayChange
  def withBackoff[In, Out, State, Mat](
      minBackoff: java.time.Duration,
      maxBackoff: java.time.Duration,
      randomFactor: Double,
      maxRetries: Int,
      flow: Flow[Pair[In, State], Pair[Try[Out], State], Mat],
      retryWith: BiFunction[
        akka.japi.tuple.Tuple3[In, Out, State],
        akka.japi.tuple.Tuple3[In, Throwable, State],
        Optional[Pair[In, State]]]): Flow[Pair[In, State], Pair[Try[Out], State], Mat] = {
    withBackoffAndContext(minBackoff, maxBackoff, randomFactor, maxRetries, FlowWithContext.fromPairs(flow), retryWith)
      .asFlow()
  }

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` bi-function. On a successful response, this function is
   * passed the response as the first argument. On a failed response, the exception is passed as the second argument.
   * The other respective argument is passed a null. `retryWith` should return one or more requests to be retried.
   *
   * If a successful response is issued a retry, the response is still propagated downstream.
   *
   * The implementation of the RetryFlow assumes that `flow` follows one-in-one-out element semantics.
   *
   * The `flow` and `retryWith` take an additional `UserCtx` parameter which can be used to correlate a request
   * with a response.
   *
   * Backoff state is tracked separately per-element coming into the wrapped `flow`.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param retryWith retry condition decision function
   */
  @ApiMayChange
  def withBackoffAndContext[In, Out, UserCtx, Mat](
      minBackoff: java.time.Duration,
      maxBackoff: java.time.Duration,
      randomFactor: Double,
      maxRetries: Int,
      flow: FlowWithContext[In, UserCtx, Try[Out], UserCtx, Mat],
      retryWith: BiFunction[
        akka.japi.tuple.Tuple3[In, Out, UserCtx],
        akka.japi.tuple.Tuple3[In, Throwable, UserCtx],
        Optional[Pair[In, UserCtx]]]): FlowWithContext[In, UserCtx, Try[Out], UserCtx, Mat] =
    scaladsl.RetryFlow
      .withBackoffAndContext[In, Out, UserCtx, Mat](
        Duration.fromNanos(minBackoff.toNanos),
        Duration.fromNanos(maxBackoff.toNanos),
        randomFactor,
        maxRetries,
        flow.asScala)({ (in, out, ctx) =>
        {
          val retryAttempt = out match {
            case Success(value)     => retryWith(akka.japi.tuple.Tuple3.create(in, value, ctx), null)
            case Failure(exception) => retryWith(null, akka.japi.tuple.Tuple3.create(in, exception, ctx))
          }
          retryAttempt.asScala.map(_.toScala)
        }
      })
      .asJava[In, UserCtx, Try[Out], UserCtx, Mat]

}
