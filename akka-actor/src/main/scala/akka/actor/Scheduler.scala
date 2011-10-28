/*
 * Copyright 2007 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Rework of David Pollak's ActorPing class in the Lift Project
 * which is licensed under the Apache 2 License.
 */
package akka.actor

import akka.AkkaException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent._
import akka.util.Duration

case class SchedulerException(msg: String, e: Throwable) extends AkkaException(msg, e) {
  def this(msg: String) = this(msg, null)
}

trait JScheduler {
  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef]
  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef]
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef]
}

abstract class Scheduler extends JScheduler {

  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef]
  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef]

  def schedule(receiver: ActorRef, message: Any, initialDelay: Duration, delay: Duration): ScheduledFuture[AnyRef] =
    schedule(receiver, message, initialDelay.toNanos, delay.toNanos, TimeUnit.NANOSECONDS)

  def schedule(f: () ⇒ Unit, initialDelay: Duration, delay: Duration): ScheduledFuture[AnyRef] =
    schedule(f, initialDelay.toNanos, delay.toNanos, TimeUnit.NANOSECONDS)

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): ScheduledFuture[AnyRef] =
    scheduleOnce(receiver, message, delay.length, delay.unit)

  def scheduleOnce(f: () ⇒ Unit, delay: Duration): ScheduledFuture[AnyRef] =
    scheduleOnce(f, delay.length, delay.unit)
}

class DefaultScheduler extends Scheduler {
  private def createSendRunnable(receiver: ActorRef, message: Any, throwWhenReceiverExpired: Boolean): Runnable = new Runnable {
    def run = {
      receiver ! message
      if (throwWhenReceiverExpired && receiver.isShutdown) throw new ActorKilledException("Receiver was terminated")
    }
  }

  private[akka] val service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)

  /**
   * Schedules to send the specified message to the receiver after initialDelay and then repeated after delay.
   * The returned java.util.concurrent.ScheduledFuture can be used to cancel the
   * send of the message.
   */
  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    try {
      service.scheduleAtFixedRate(createSendRunnable(receiver, message, true), initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception ⇒ throw SchedulerException(message + " could not be scheduled on " + receiver, e)
    }
  }

  /**
   * Schedules to run specified function to the receiver after initialDelay and then repeated after delay,
   * avoid blocking operations since this is executed in the schedulers thread.
   * The returned java.util.concurrent.ScheduledFuture can be used to cancel the
   * execution of the function.
   */
  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] =
    schedule(new Runnable { def run = f() }, initialDelay, delay, timeUnit)

  /**
   * Schedules to run specified runnable to the receiver after initialDelay and then repeated after delay,
   * avoid blocking operations since this is executed in the schedulers thread.
   * The returned java.util.concurrent.ScheduledFuture can be used to cancel the
   * execution of the runnable.
   */
  def schedule(runnable: Runnable, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    try {
      service.scheduleAtFixedRate(runnable, initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception ⇒ throw SchedulerException("Failed to schedule a Runnable", e)
    }
  }

  /**
   * Schedules to send the specified message to the receiver after delay.
   * The returned java.util.concurrent.ScheduledFuture can be used to cancel the
   * send of the message.
   */
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    try {
      service.schedule(createSendRunnable(receiver, message, false), delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception ⇒ throw SchedulerException(message + " could not be scheduleOnce'd on " + receiver, e)
    }
  }

  /**
   * Schedules a function to be run after delay,
   * avoid blocking operations since the runnable is executed in the schedulers thread.
   * The returned java.util.concurrent.ScheduledFuture can be used to cancel the
   * execution of the function.
   */
  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] =
    scheduleOnce(new Runnable { def run = f() }, delay, timeUnit)

  /**
   * Schedules a runnable to be run after delay,
   * avoid blocking operations since the runnable is executed in the schedulers thread.
   * The returned java.util.concurrent.ScheduledFuture can be used to cancel the
   * execution of the runnable.
   */
  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    try {
      service.schedule(runnable, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception ⇒ throw SchedulerException("Failed to scheduleOnce a Runnable", e)
    }
  }

  private[akka] def shutdown() { service.shutdownNow() }
}

private object SchedulerThreadFactory extends ThreadFactory {
  private val count = new AtomicLong(0)
  val threadFactory = Executors.defaultThreadFactory()

  def newThread(r: Runnable): Thread = {
    val thread = threadFactory.newThread(r)
    thread.setName("akka:scheduler-" + count.incrementAndGet())
    thread.setDaemon(true)
    thread
  }
}
