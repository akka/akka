/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import akka.actor.{ Scheduler, Cancellable }
import scala.concurrent.util.Duration

/**
 * INTERNAL API
 */
private[akka] object FixedRateTask {
  def apply(scheduler: Scheduler, initalDelay: Duration, delay: Duration)(f: ⇒ Unit): FixedRateTask =
    new FixedRateTask(scheduler, initalDelay, delay, new Runnable { def run(): Unit = f })
}

/**
 * INTERNAL API
 *
 * Task to be scheduled periodically at a fixed rate, compensating, on average,
 * for inaccuracy in scheduler. It will start when constructed, using the
 * initialDelay.
 */
private[akka] class FixedRateTask(scheduler: Scheduler, initalDelay: Duration, delay: Duration, task: Runnable)
  extends Runnable with Cancellable {

  private val delayNanos = delay.toNanos
  private val cancelled = new AtomicBoolean(false)
  private val counter = new AtomicLong(0L)
  private val startTime = System.nanoTime + initalDelay.toNanos
  scheduler.scheduleOnce(initalDelay, this)

  def cancel(): Unit = cancelled.set(true)

  def isCancelled: Boolean = cancelled.get

  override final def run(): Unit = if (!isCancelled) try {
    task.run()
  } finally if (!isCancelled) {
    val nextTime = startTime + delayNanos * counter.incrementAndGet
    // it's ok to schedule with negative duration, will run asap
    val nextDelay = Duration(nextTime - System.nanoTime, TimeUnit.NANOSECONDS)
    try {
      scheduler.scheduleOnce(nextDelay, this)
    } catch { case e: IllegalStateException ⇒ /* will happen when scheduler is closed, nothing wrong */ }
  }

}
