/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import java.time.Duration

import akka.actor.Cancellable
import akka.actor.typed.Scheduler
import akka.annotation.InternalApi

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi final class SchedulerAdapter(untypedScheduler: akka.actor.Scheduler) extends Scheduler {
  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(
      implicit executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleOnce(delay, runnable)

  override def scheduleOnce(delay: Duration, runnable: Runnable, executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleOnce(delay, runnable)(executor)

  override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, runnable: Runnable)(
      implicit executor: ExecutionContext): Cancellable =
    untypedScheduler.schedule(initialDelay, interval, runnable)

  override def schedule(
      initialDelay: Duration,
      interval: Duration,
      runnable: Runnable,
      executor: ExecutionContext): Cancellable =
    untypedScheduler.schedule(initialDelay, interval, runnable)(executor)
}
