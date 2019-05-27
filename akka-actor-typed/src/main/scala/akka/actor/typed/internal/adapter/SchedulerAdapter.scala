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
@InternalApi
private[akka] final class SchedulerAdapter(private[akka] val untypedScheduler: akka.actor.Scheduler) extends Scheduler {
  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(
      implicit executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleOnce(delay, runnable)

  override def scheduleOnce(delay: Duration, runnable: Runnable, executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleOnce(delay, runnable)(executor)

  override def scheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration)(runnable: Runnable)(
      implicit executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleWithFixedDelay(initialDelay, delay)(runnable)

  override def scheduleWithFixedDelay(
      initialDelay: Duration,
      delay: Duration,
      runnable: Runnable,
      executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleWithFixedDelay(initialDelay, delay, runnable, executor)

  override def scheduleAtFixedRate(initialDelay: FiniteDuration, interval: FiniteDuration)(runnable: Runnable)(
      implicit executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleAtFixedRate(initialDelay, interval)(runnable)

  override def scheduleAtFixedRate(
      initialDelay: Duration,
      interval: Duration,
      runnable: Runnable,
      executor: ExecutionContext): Cancellable =
    untypedScheduler.scheduleAtFixedRate(initialDelay, interval, runnable, executor)

}
