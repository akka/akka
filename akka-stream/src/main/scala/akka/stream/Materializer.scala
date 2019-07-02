/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.actor.Cancellable
import akka.annotation.InternalApi
import com.github.ghik.silencer.silent

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

/**
 * Materializer SPI (Service Provider Interface)
 *
 * Binary compatibility is NOT guaranteed on materializer internals.
 *
 * Custom materializer implementations should be aware that the materializer SPI
 * is not yet final and may change in patch releases of Akka. Please note that this
 * does not impact end-users of Akka streams, only implementors of custom materializers,
 * with whom the Akka team co-ordinates such changes.
 *
 * Once the SPI is final this notice will be removed.
 */
@silent // deprecatedName(symbol) is deprecated but older Scala versions don't have a string signature, since "2.5.8"
abstract class Materializer {

  /**
   * The `namePrefix` shall be used for deriving the names of processing
   * entities that are created during materialization. This is meant to aid
   * logging and failure reporting both during materialization and while the
   * stream is running.
   */
  def withNamePrefix(name: String): Materializer

  /**
   * This method interprets the given Flow description and creates the running
   * stream. The result can be highly implementation specific, ranging from
   * local actor chains to remote-deployed processing networks.
   */
  def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat

  /**
   * This method interprets the given Flow description and creates the running
   * stream using an explicitly provided [[Attributes]] as top level (least specific) attributes that
   * will be defaults for the materialized stream.
   * The result can be highly implementation specific, ranging from local actor chains to remote-deployed
   * processing networks.
   */
  def materialize[Mat](
      runnable: Graph[ClosedShape, Mat],
      @deprecatedName(Symbol("initialAttributes")) defaultAttributes: Attributes): Mat

  /**
   * Running a flow graph will require execution resources, as will computations
   * within Sources, Sinks, etc. This [[scala.concurrent.ExecutionContextExecutor]]
   * can be used by parts of the flow to submit processing jobs for execution,
   * run Future callbacks, etc.
   *
   * Note that this is not necessarily the same execution context the stream operator itself is running on.
   */
  implicit def executionContext: ExecutionContextExecutor

  /**
   * Interface for operators that need timer services for their functionality. Schedules a
   * single task with the given delay.
   *
   * @return A [[akka.actor.Cancellable]] that allows cancelling the timer. Cancelling is best effort, if the event
   *         has been already enqueued it will not have an effect.
   */
  def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable

  /**
   * Interface for operators that need timer services for their functionality.
   *
   * Schedules a `Runnable` to be run repeatedly with an initial delay and
   * a fixed `delay` between subsequent executions.
   *
   * It will not compensate the delay between tasks if the execution takes a long time or if
   * scheduling is delayed longer than specified for some reason. The delay between subsequent
   * execution will always be (at least) the given `delay`. In the long run, the
   * frequency of execution will generally be slightly lower than the reciprocal of the specified
   * `delay`.
   *
   * If the `Runnable` throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   *   supported by the `Scheduler`.
   *
   * @return A [[akka.actor.Cancellable]] that allows cancelling the timer. Cancelling is best effort, if the event
   *         has been already enqueued it will not have an effect.
   */
  def scheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration, task: Runnable): Cancellable

  /**
   * Interface for operators that need timer services for their functionality.
   *
   * Schedules a `Runnable` to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set `delay=Duration(2, TimeUnit.SECONDS)`
   * and `interval=Duration(100, TimeUnit.MILLISECONDS)`.
   *
   * It will compensate the delay for a subsequent task if the previous tasks took
   * too long to execute. In such cases, the actual execution interval will differ from
   * the interval passed to the method.
   *
   * If the execution of the tasks takes longer than the `interval`, the subsequent
   * execution will start immediately after the prior one completes (there will be
   * no overlap of executions). This also has the consequence that after long garbage
   * collection pauses or other reasons when the JVM was suspended all "missed" tasks
   * will execute when the process wakes up again.
   *
   * In the long run, the frequency of execution will be exactly the reciprocal of the
   * specified `interval`.
   *
   * Warning: `scheduleAtFixedRate` can result in bursts of scheduled tasks after long
   * garbage collection pauses, which may in worst case cause undesired load on the system.
   * Therefore `scheduleWithFixedDelay` is often preferred.
   *
   * If the `Runnable` throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   *   supported by the `Scheduler`.
   *
   * @return A [[akka.actor.Cancellable]] that allows cancelling the timer. Cancelling is best effort, if the event
   *         has been already enqueued it will not have an effect.
   */
  def scheduleAtFixedRate(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable

  /**
   * Interface for operators that need timer services for their functionality. Schedules a
   * repeated task with the given interval between invocations.
   *
   * @return A [[akka.actor.Cancellable]] that allows cancelling the timer. Cancelling is best effort, if the event
   *         has been already enqueued it will not have an effect.
   */
  @deprecated(
    "Use scheduleWithFixedDelay or scheduleAtFixedRate instead. This has the same semantics as " +
    "scheduleAtFixedRate, but scheduleWithFixedDelay is often preferred.",
    since = "2.6.0")
  def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object NoMaterializer extends Materializer {
  override def withNamePrefix(name: String): Materializer =
    throw new UnsupportedOperationException("NoMaterializer cannot be named")
  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat =
    throw new UnsupportedOperationException("NoMaterializer cannot materialize")
  override def materialize[Mat](runnable: Graph[ClosedShape, Mat], defaultAttributes: Attributes): Mat =
    throw new UnsupportedOperationException("NoMaterializer cannot materialize")

  override def executionContext: ExecutionContextExecutor =
    throw new UnsupportedOperationException("NoMaterializer does not provide an ExecutionContext")

  def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot schedule a single event")

  def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot schedule a repeated event")

  override def scheduleWithFixedDelay(
      initialDelay: FiniteDuration,
      delay: FiniteDuration,
      task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot scheduleWithFixedDelay")

  override def scheduleAtFixedRate(
      initialDelay: FiniteDuration,
      interval: FiniteDuration,
      task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot scheduleAtFixedRate")
}

/**
 * Context parameter to the `create` methods of sources and sinks.
 *
 * INTERNAL API
 */
@InternalApi
private[akka] case class MaterializationContext(
    materializer: Materializer,
    effectiveAttributes: Attributes,
    islandName: String)
