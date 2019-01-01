/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.actor.Cancellable
import akka.annotation.InternalApi

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
    runnable:                                              Graph[ClosedShape, Mat],
    @deprecatedName('initialAttributes) defaultAttributes: Attributes): Mat

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
   * Interface for operators that need timer services for their functionality. Schedules a
   * repeated task with the given interval between invocations.
   *
   * @return A [[akka.actor.Cancellable]] that allows cancelling the timer. Cancelling is best effort, if the event
   *         has been already enqueued it will not have an effect.
   */
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
}

/**
 * Context parameter to the `create` methods of sources and sinks.
 *
 * INTERNAL API
 */
@InternalApi
private[akka] case class MaterializationContext(
  materializer:        Materializer,
  effectiveAttributes: Attributes,
  islandName:          String)
