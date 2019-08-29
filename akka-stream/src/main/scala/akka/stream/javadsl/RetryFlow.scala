/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util
import java.util.Optional
import java.util.function.BiFunction

import akka.NotUsed
import akka.japi.Pair
import akka.stream.scaladsl.RetryFlow.withBackoffAndContext
import akka.util.ccompat.JavaConverters._

import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

object RetryFlow {

  /**
   * Allows retrying individual elements in the stream with exponential backoff.
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
   * @param parallelism controls the number of in-flight requests in the wrapped flow
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param retryWith retry condition decision function
   */
  def withBackoff[In, Out, State, Mat](
      parallelism: Int,
      minBackoff: java.time.Duration,
      maxBackoff: java.time.Duration,
      randomFactor: Double,
      flow: Flow[Pair[In, State], Pair[Try[Out], State], Mat],
      retryWith: BiFunction[Pair[Out, State], Pair[Throwable, State], Optional[util.Collection[Pair[In, State]]]])
      : Flow[akka.japi.Pair[In, State], akka.japi.Pair[Try[Out], State], Mat] = {
    val retryFlow = withBackoffAndContext(
      parallelism,
      Duration.fromNanos(minBackoff.toNanos),
      Duration.fromNanos(maxBackoff.toNanos),
      randomFactor,
      FlowWithContext.fromPairs(flow).asScala) { case result =>
        val retryAttempt = result match {
          case (Success(value), s) => retryWith(Pair.create(value, s), null)
          case (Failure(exception), s) => retryWith(null, Pair.create(exception, s))
        }
        retryAttempt.asScala.map(coll => coll.asScala.toIndexedSeq.map(pair => (pair.first, pair.second)))
    }.asFlow

    Flow
      .create[akka.japi.Pair[In, State]]()
      .map(p => (p.first, p.second))
      .viaMat(retryFlow, Keep.right[NotUsed, Mat])
      .map(t => akka.japi.Pair.create(t._1, t._2))
  }

}
