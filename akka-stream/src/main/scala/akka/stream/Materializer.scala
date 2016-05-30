/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import akka.actor.Cancellable

import scala.concurrent.ExecutionContextExecutor

import scala.concurrent.duration.FiniteDuration

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
   * Running a flow graph will require execution resources, as will computations
   * within Sources, Sinks, etc. This [[scala.concurrent.ExecutionContextExecutor]]
   * can be used by parts of the flow to submit processing jobs for execution,
   * run Future callbacks, etc.
   */
  implicit def executionContext: ExecutionContextExecutor

  /**
   * Interface for stages that need timer services for their functionality. Schedules a
   * single task with the given delay.
   *
   * @return A [[akka.actor.Cancellable]] that allows cancelling the timer. Cancelling is best effort, if the event
   *         has been already enqueued it will not have an effect.
   */
  def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable

  /**
   * Interface for stages that need timer services for their functionality. Schedules a
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
private[akka] object NoMaterializer extends Materializer {
  override def withNamePrefix(name: String): Materializer =
    throw new UnsupportedOperationException("NoMaterializer cannot be named")
  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat =
    throw new UnsupportedOperationException("NoMaterializer cannot materialize")
  override def executionContext: ExecutionContextExecutor =
    throw new UnsupportedOperationException("NoMaterializer does not provide an ExecutionContext")

  def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot schedule a single event")

  def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot schedule a repeated event")
}

/**
 * INTERNAL API: this might become public later
 *
 * Context parameter to the `create` methods of sources and sinks.
 */
private[akka] case class MaterializationContext(
  materializer:        Materializer,
  effectiveAttributes: Attributes,
  stageName:           String)
