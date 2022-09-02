/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.{ Attributes, Inlet, RestartSettings, SinkShape }

import scala.concurrent.duration.FiniteDuration

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
   * This uses the same exponential backoff algorithm as [[akka.pattern.BackoffOpts]].
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
  @deprecated("Use the overloaded method which accepts akka.stream.RestartSettings instead.", since = "2.6.10")
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(
      sinkFactory: () => Sink[T, _]): Sink[T, NotUsed] = {
    val settings = RestartSettings(minBackoff, maxBackoff, randomFactor)
    withBackoff(settings)(sinkFactory)
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
   * This uses the same exponential backoff algorithm as [[akka.pattern.BackoffOpts]].
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
  @deprecated("Use the overloaded method which accepts akka.stream.RestartSettings instead.", since = "2.6.10")
  def withBackoff[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)(
      sinkFactory: () => Sink[T, _]): Sink[T, NotUsed] = {
    val settings = RestartSettings(minBackoff, maxBackoff, randomFactor).withMaxRestarts(maxRestarts, minBackoff)
    withBackoff(settings)(sinkFactory)
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
   * This uses the same exponential backoff algorithm as [[akka.pattern.BackoffOpts]].
   *
   * @param settings [[RestartSettings]] defining restart configuration
   * @param sinkFactory A factory for producing the [[Sink]] to wrap.
   */
  def withBackoff[T](settings: RestartSettings)(sinkFactory: () => Sink[T, _]): Sink[T, NotUsed] =
    Sink.fromGraph(new RestartWithBackoffSink(sinkFactory, settings))
}

private final class RestartWithBackoffSink[T](sinkFactory: () => Sink[T, _], restartSettings: RestartSettings)
    extends GraphStage[SinkShape[T]] { self =>

  val in = Inlet[T]("RestartWithBackoffSink.in")

  override def shape = SinkShape(in)
  override def createLogic(inheritedAttributes: Attributes) =
    new RestartWithBackoffLogic("Sink", shape, inheritedAttributes, restartSettings, onlyOnFailures = false) {
      override protected def logSource = self.getClass

      override protected def startGraph() = {
        val sourceOut = createSubOutlet(in)
        subFusingMaterializer.materialize(Source.fromGraph(sourceOut.source).to(sinkFactory()), inheritedAttributes)
      }

      override protected def backoff() = {
        setHandler(in, GraphStageLogic.EagerTerminateInput)
      }

      backoff()
    }
}
