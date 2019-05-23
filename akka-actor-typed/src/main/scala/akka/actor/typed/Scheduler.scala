/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.Cancellable
import akka.annotation.DoNotInherit

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
 * The ActorSystem facility for scheduling tasks.
 *
 * For scheduling within actors `Behaviors.withTimers` should be preferred.
 *
 * Not for user extension
 */
@DoNotInherit
trait Scheduler {

  /**
   *
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Note: For scheduling within actors `Behaviors.withTimers` or `ActorContext.scheduleOnce` should be preferred.
   *
   * Scala API
   */
  def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Note: For scheduling within actors `Behaviors.withTimers` or `ActorContext.scheduleOnce` should be preferred.
   *
   * Java API
   */
  def scheduleOnce(delay: java.time.Duration, runnable: Runnable, executor: ExecutionContext): Cancellable

  /**
   * Schedules a `Runnable` to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay = Duration(2,
   * TimeUnit.SECONDS) and interval = Duration(100, TimeUnit.MILLISECONDS). If
   * the execution of the runnable takes longer than the interval, the
   * subsequent execution will start immediately after the prior one completes
   * (there will be no overlap of executions of the runnable). In such cases,
   * the actual execution interval will differ from the interval passed to this
   * method.
   *
   * If the `Runnable` throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Note: For user scheduling needs `Behaviors.withTimers` should be preferred.
   *
   * Scala API
   */
  def scheduleAtFixedRate(initialDelay: FiniteDuration, interval: FiniteDuration, runnable: Runnable)(
      implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a `Runnable` to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay to `Duration.ofSeconds(2)`,
   * and interval to `Duration.ofMillis(100)`. If
   * the execution of the runnable takes longer than the interval, the
   * subsequent execution will start immediately after the prior one completes
   * (there will be no overlap of executions of the runnable). In such cases,
   * the actual execution interval will differ from the interval passed to this
   * method.
   *
   * If the `Runnable` throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Note: For user scheduling needs `Behaviors.withTimers` should be preferred.
   *
   * Java API
   */
  def scheduleAtFixedRate(
      initialDelay: java.time.Duration,
      interval: java.time.Duration,
      runnable: Runnable,
      executor: ExecutionContext): Cancellable
}
