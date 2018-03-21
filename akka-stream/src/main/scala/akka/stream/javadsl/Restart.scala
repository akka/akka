/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.NotUsed
import akka.japi.function.Creator

import scala.concurrent.duration.FiniteDuration

/**
 * A RestartSource wraps a [[Source]] that gets restarted when it completes or fails.
 *
 * They are useful for graphs that need to run for longer than the [[Source]] can necessarily guarantee it will, for
 * example, for [[Source]] streams that depend on a remote server that may crash or become partitioned. The
 * RestartSource ensures that the graph can continue running while the [[Source]] restarts.
 */
object RestartSource {

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Source]] will never emit a complete or failure, since the completion or failure of the wrapped [[Source]]
   * is always handled by restarting it. The wrapped [[Source]] can however be cancelled by cancelling this [[Source]].
   * When that happens, the wrapped [[Source]], if currently running will be cancelled, and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                     sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    akka.stream.scaladsl.RestartSource.withBackoff(minBackoff, maxBackoff, randomFactor) { () ⇒
      sourceFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Source]] will never emit a complete or failure, since the completion or failure of the wrapped [[Source]]
   * is always handled by restarting it. The wrapped [[Source]] can however be cancelled by cancelling this [[Source]].
   * When that happens, the wrapped [[Source]], if currently running will be cancelled, and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   */
  def withBackoff[T](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                     sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    import akka.util.JavaDurationConverters._
    withBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, sourceFactory)
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Source]] will not emit a complete or failure as long as maxRestarts is not reached, since the completion
   * or failure of the wrapped [[Source]] is handled by restarting it. The wrapped [[Source]] can however be cancelled
   * by cancelling this [[Source]]. When that happens, the wrapped [[Source]], if currently running will be cancelled,
   * and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                     maxRestarts: Int, sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    akka.stream.scaladsl.RestartSource.withBackoff(minBackoff, maxBackoff, randomFactor, maxRestarts) { () ⇒
      sourceFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Source]] will not emit a complete or failure as long as maxRestarts is not reached, since the completion
   * or failure of the wrapped [[Source]] is handled by restarting it. The wrapped [[Source]] can however be cancelled
   * by cancelling this [[Source]]. When that happens, the wrapped [[Source]], if currently running will be cancelled,
   * and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   */
  def withBackoff[T](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                     maxRestarts: Int, sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    import akka.util.JavaDurationConverters._
    withBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, maxRestarts, sourceFactory)
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails using an exponential backoff.
   *
   * This [[Source]] will never emit a failure, since the failure of the wrapped [[Source]] is always handled by
   * restarting. The wrapped [[Source]] can be cancelled by cancelling this [[Source]].
   * When that happens, the wrapped [[Source]], if currently running will be cancelled, and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   *
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def onFailuresWithBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                               sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    akka.stream.scaladsl.RestartSource.onFailuresWithBackoff(minBackoff, maxBackoff, randomFactor) { () ⇒
      sourceFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails using an exponential backoff.
   *
   * This [[Source]] will never emit a failure, since the failure of the wrapped [[Source]] is always handled by
   * restarting. The wrapped [[Source]] can be cancelled by cancelling this [[Source]].
   * When that happens, the wrapped [[Source]], if currently running will be cancelled, and it will not be restarted.
   * This can be triggered simply by the downstream cancelling, or externally by introducing a [[KillSwitch]] right
   * after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   *
   */
  def onFailuresWithBackoff[T](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                               sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    import akka.util.JavaDurationConverters._
    onFailuresWithBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, sourceFactory)
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails using an exponential backoff.
   *
   * This [[Source]] will not emit a complete or failure as long as maxRestarts is not reached, since the completion
   * or failure of the wrapped [[Source]] is handled by restarting it. The wrapped [[Source]] can however be cancelled
   * by cancelling this [[Source]]. When that happens, the wrapped [[Source]], if currently running will be cancelled,
   * and it will not be restarted. This can be triggered simply by the downstream cancelling, or externally by
   * introducing a [[KillSwitch]] right after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   *
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def onFailuresWithBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                               maxRestarts: Int, sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    akka.stream.scaladsl.RestartSource.onFailuresWithBackoff(minBackoff, maxBackoff, randomFactor, maxRestarts) { () ⇒
      sourceFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Source]] with a [[Source]] that will restart it when it fails using an exponential backoff.
   *
   * This [[Source]] will not emit a complete or failure as long as maxRestarts is not reached, since the completion
   * or failure of the wrapped [[Source]] is handled by restarting it. The wrapped [[Source]] can however be cancelled
   * by cancelling this [[Source]]. When that happens, the wrapped [[Source]], if currently running will be cancelled,
   * and it will not be restarted. This can be triggered simply by the downstream cancelling, or externally by
   * introducing a [[KillSwitch]] right after this [[Source]] in the graph.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param sourceFactory A factory for producing the [[Source]] to wrap.
   *
   */
  def onFailuresWithBackoff[T](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                               maxRestarts: Int, sourceFactory: Creator[Source[T, _]]): Source[T, NotUsed] = {
    import akka.util.JavaDurationConverters._
    onFailuresWithBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, maxRestarts, sourceFactory)
  }
}

/**
 * A RestartSink wraps a [[Sink]] that gets restarted when it completes or fails.
 *
 * They are useful for graphs that need to run for longer than the [[Sink]] can necessarily guarantee it will, for
 * example, for [[Sink]] streams that depend on a remote server that may crash or become partitioned. The
 * RestartSink ensures that the graph can continue running while the [[Sink]] restarts.
 */
object RestartSink {

  /**
   * Wrap the given [[Sink]] with a [[Sink]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Sink]] will never cancel, since cancellation by the wrapped [[Sink]] is always handled by restarting it.
   * The wrapped [[Sink]] can however be completed by feeding a completion or error into this [[Sink]]. When that
   * happens, the [[Sink]], if currently running, will terminate and will not be restarted. This can be triggered
   * simply by the upstream completing, or externally by introducing a [[KillSwitch]] right before this [[Sink]] in the
   * graph.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. When the wrapped [[Sink]] does cancel, this [[Sink]] will backpressure, however any elements already
   * sent may have been lost.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                     sinkFactory: Creator[Sink[T, _]]): Sink[T, NotUsed] = {
    akka.stream.scaladsl.RestartSink.withBackoff(minBackoff, maxBackoff, randomFactor) { () ⇒
      sinkFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Sink]] with a [[Sink]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Sink]] will never cancel, since cancellation by the wrapped [[Sink]] is always handled by restarting it.
   * The wrapped [[Sink]] can however be completed by feeding a completion or error into this [[Sink]]. When that
   * happens, the [[Sink]], if currently running, will terminate and will not be restarted. This can be triggered
   * simply by the upstream completing, or externally by introducing a [[KillSwitch]] right before this [[Sink]] in the
   * graph.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. When the wrapped [[Sink]] does cancel, this [[Sink]] will backpressure, however any elements already
   * sent may have been lost.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  def withBackoff[T](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                     sinkFactory: Creator[Sink[T, _]]): Sink[T, NotUsed] = {
    import akka.util.JavaDurationConverters._
    withBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, sinkFactory)
  }

  /**
   * Wrap the given [[Sink]] with a [[Sink]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Sink]] will not cancel as long as maxRestarts is not reached, since cancellation by the wrapped [[Sink]]
   * is handled by restarting it. The wrapped [[Sink]] can however be completed by feeding a completion or error into
   * this [[Sink]]. When that happens, the [[Sink]], if currently running, will terminate and will not be restarted.
   * This can be triggered simply by the upstream completing, or externally by introducing a [[KillSwitch]] right
   * before this [[Sink]] in the graph.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. When the wrapped [[Sink]] does cancel, this [[Sink]] will backpressure, however any elements already
   * sent may have been lost.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                     maxRestarts: Int, sinkFactory: Creator[Sink[T, _]]): Sink[T, NotUsed] = {
    akka.stream.scaladsl.RestartSink.withBackoff(minBackoff, maxBackoff, randomFactor, maxRestarts) { () ⇒
      sinkFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Sink]] with a [[Sink]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Sink]] will not cancel as long as maxRestarts is not reached, since cancellation by the wrapped [[Sink]]
   * is handled by restarting it. The wrapped [[Sink]] can however be completed by feeding a completion or error into
   * this [[Sink]]. When that happens, the [[Sink]], if currently running, will terminate and will not be restarted.
   * This can be triggered simply by the upstream completing, or externally by introducing a [[KillSwitch]] right
   * before this [[Sink]] in the graph.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. When the wrapped [[Sink]] does cancel, this [[Sink]] will backpressure, however any elements already
   * sent may have been lost.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  def withBackoff[T](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                     maxRestarts: Int, sinkFactory: Creator[Sink[T, _]]): Sink[T, NotUsed] = {
    import akka.util.JavaDurationConverters._
    withBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, maxRestarts, sinkFactory)
  }
}

/**
 * A RestartFlow wraps a [[Flow]] that gets restarted when it completes or fails.
 *
 * They are useful for graphs that need to run for longer than the [[Flow]] can necessarily guarantee it will, for
 * example, for [[Flow]] streams that depend on a remote server that may crash or become partitioned. The
 * RestartFlow ensures that the graph can continue running while the [[Flow]] restarts.
 */
object RestartFlow {

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Flow]] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
   * completed. Any termination by the [[Flow]] before that time will be handled by restarting it. Any termination
   * signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's running, and then the [[Flow]]
   * will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def withBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                           flowFactory: Creator[Flow[In, Out, _]]): Flow[In, Out, NotUsed] = {
    akka.stream.scaladsl.RestartFlow.withBackoff(minBackoff, maxBackoff, randomFactor) { () ⇒
      flowFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Flow]] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
   * completed. Any termination by the [[Flow]] before that time will be handled by restarting it. Any termination
   * signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's running, and then the [[Flow]]
   * will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  def withBackoff[In, Out](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                           flowFactory: Creator[Flow[In, Out, _]]): Flow[In, Out, NotUsed] = {
    import akka.util.JavaDurationConverters._
    withBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, flowFactory)
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Flow]] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
   * completed. Any termination by the [[Flow]] before that time will be handled by restarting it as long as maxRestarts
   * is not reached. Any termination signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's
   * running, and then the [[Flow]] will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def withBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                           maxRestarts: Int, flowFactory: Creator[Flow[In, Out, _]]): Flow[In, Out, NotUsed] = {
    akka.stream.scaladsl.RestartFlow.withBackoff(minBackoff, maxBackoff, randomFactor, maxRestarts) { () ⇒
      flowFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart it when it fails or complete using an exponential
   * backoff.
   *
   * This [[Flow]] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
   * completed. Any termination by the [[Flow]] before that time will be handled by restarting it as long as maxRestarts
   * is not reached. Any termination signals sent to this [[Flow]] however will terminate the wrapped [[Flow]], if it's
   * running, and then the [[Flow]] will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  def withBackoff[In, Out](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                           maxRestarts: Int, flowFactory: Creator[Flow[In, Out, _]]): Flow[In, Out, NotUsed] = {
    import akka.util.JavaDurationConverters._
    withBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, maxRestarts, flowFactory)
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart only when it fails that restarts
   * using an exponential backoff.
   *
   * This new [[Flow]] will not emit failures. Any failure by the original [[Flow]] (the wrapped one) before that
   * time will be handled by restarting it as long as maxRestarts  is not reached.
   * However, any termination signals, completion or cancellation sent to this [[Flow]] will terminate
   * the wrapped [[Flow]], if it's running, and then the [[Flow]] will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def onFailuresWithBackoff[In, Out](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double,
                                     maxRestarts: Int, flowFactory: Creator[Flow[In, Out, _]]): Flow[In, Out, NotUsed] = {
    akka.stream.scaladsl.RestartFlow.onFailuresWithBackoff(minBackoff, maxBackoff, randomFactor, maxRestarts) { () ⇒
      flowFactory.create().asScala
    }.asJava
  }

  /**
   * Wrap the given [[Flow]] with a [[Flow]] that will restart only when it fails that restarts
   * using an exponential backoff.
   *
   * This new [[Flow]] will not emit failures. Any failure by the original [[Flow]] (the wrapped one) before that
   * time will be handled by restarting it as long as maxRestarts  is not reached.
   * However, any termination signals, completion or cancellation sent to this [[Flow]] will terminate
   * the wrapped [[Flow]], if it's running, and then the [[Flow]] will be allowed to terminate without being restarted.
   *
   * The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
   * messages. A termination signal from either end of the wrapped [[Flow]] will cause the other end to be terminated,
   * and any in transit messages will be lost. During backoff, this [[Flow]] will backpressure.
   *
   * This uses the same exponential backoff algorithm as [[akka.pattern.Backoff]].
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   * @param maxRestarts the amount of restarts is capped to this amount within a time frame of minBackoff.
   *   Passing `0` will cause no restarts and a negative number will not cap the amount of restarts.
   * @param flowFactory A factory for producing the [[Flow]] to wrap.
   */
  def onFailuresWithBackoff[In, Out](minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double,
                                     maxRestarts: Int, flowFactory: Creator[Flow[In, Out, _]]): Flow[In, Out, NotUsed] = {
    import akka.util.JavaDurationConverters._
    onFailuresWithBackoff(minBackoff.asScala, maxBackoff.asScala, randomFactor, maxRestarts, flowFactory)
  }
}
