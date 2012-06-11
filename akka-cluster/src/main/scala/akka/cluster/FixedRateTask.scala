/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import akka.actor.Scheduler
import akka.util.Duration

/**
 * INTERNAL API
 */
private[akka] object FixedRateTask {
  def apply(scheduler: Scheduler, initalDelay: Duration, delay: Duration)(f: ⇒ Unit): FixedRateTask = {
    new FixedRateTask(scheduler, initalDelay, delay, new Runnable { def run(): Unit = f })
  }
}

/**
 * INTERNAL API
 *
 * Task to be scheduled periodically at a fixed rate, compensating, on average,
 * for inaccuracy in scheduler. It will start when constructed, using the
 * initialDelay.
 */
private[akka] class FixedRateTask(scheduler: Scheduler, initalDelay: Duration, delay: Duration, task: Runnable) extends Runnable {

  private val delayMillis = delay.toMillis
  private val minDelayMillis = 1L
  private val cancelled = new AtomicBoolean(false)
  private val counter = new AtomicLong(0L)
  private val startTime = System.currentTimeMillis + initalDelay.toMillis
  scheduler.scheduleOnce(initalDelay, this)

  def cancel(): Unit = cancelled.set(true)

  override final def run(): Unit = if (!cancelled.get) try {
    task.run()
  } finally if (!cancelled.get) {
    val nextTime = startTime + delayMillis * counter.incrementAndGet
    val nextDelayMillis = nextTime - System.currentTimeMillis
    val nextDelay = Duration(
      (if (nextDelayMillis <= minDelayMillis) minDelayMillis else nextDelayMillis),
      TimeUnit.MILLISECONDS)
    try {
      scheduler.scheduleOnce(nextDelay, this)
    } catch { case e: IllegalStateException ⇒ /* will happen when scheduler is closed, nothing wrong */ }
  }

}
