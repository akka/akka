/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util.Optional

import akka.annotation.ApiMayChange
import akka.japi.Pair
import akka.stream.scaladsl
import akka.util.JavaDurationConverters._

import scala.compat.java8.OptionConverters._

object RetryFlow {

  /**
   * API may change!
   *
   * Allows retrying individual elements in the stream with an exponential backoff.
   *
   * The retry condition is controlled by the `decideRetry` function. It takes the originally emitted
   * element with its context, and the response emitted by `flow`, and may return a request to be retried.
   *
   * The implementation of the `RetryFlow` requires that `flow` follows one-in-one-out semantics,
   * the [[akka.stream.javadsl.FlowWithContext FlowWithContext]] may not filter elements,
   * nor emit more than one element per incoming element.
   *
   * The wrapped `flow` and `decideRetry` take the additional context parameters which can be a context,
   * or used to control retrying with other information.
   *
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param decideRetry retry condition decision function
   */
  @ApiMayChange
  def withBackoffAndContext[In, InCtx, Out, OutCtx, Mat](
      minBackoff: java.time.Duration,
      maxBackoff: java.time.Duration,
      randomFactor: Double,
      maxRetries: Int,
      flow: FlowWithContext[In, InCtx, Out, OutCtx, Mat],
      decideRetry: akka.japi.function.Function2[Pair[In, InCtx], Pair[Out, OutCtx], Optional[Pair[In, InCtx]]])
      : FlowWithContext[In, InCtx, Out, OutCtx, Mat] =
    scaladsl.RetryFlow
      .withBackoffAndContext[In, InCtx, Out, OutCtx, Mat](
        minBackoff.asScala,
        maxBackoff.asScala,
        randomFactor,
        maxRetries,
        flow.asScala) { (in, out) =>
        decideRetry.apply(Pair(in._1, in._2), Pair(out._1, out._2)).asScala.map(_.toScala)
      }
      .asJava[In, InCtx, Out, OutCtx, Mat]

}
