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
