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
package se.scalablesolutions.akka.actor

import scala.collection.JavaConversions
import java.util.concurrent._

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.AkkaException

object Scheduler extends Logging {
  import Actor._

  case class SchedulerException(msg: String, e: Throwable) extends RuntimeException(msg, e)

  private var service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)

  log.info("Starting up Scheduler")

  /**
   * Schedules to send the specified message to the receiver after initialDelay and then repeated after delay
   */
  def schedule(receiver: ActorRef, message: AnyRef, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    log.trace(
      "Schedule scheduled event\n\tevent = [%s]\n\treceiver = [%s]\n\tinitialDelay = [%s]\n\tdelay = [%s]\n\ttimeUnit = [%s]",
      message, receiver, initialDelay, delay, timeUnit)
    try {
      service.scheduleAtFixedRate(
        new Runnable { def run = receiver ! message },
        initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception => throw SchedulerException(message + " could not be scheduled on " + receiver, e)
    }
  }

  /**
   * Schedules to run specified function to the receiver after initialDelay and then repeated after delay,
   * avoid blocking operations since this is executed in the schedulers thread
   */
  def schedule(f: () => Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] =
    schedule(new Runnable { def run = f() }, initialDelay, delay, timeUnit)

  /**
   * Schedules to run specified runnable to the receiver after initialDelay and then repeated after delay,
   * avoid blocking operations since this is executed in the schedulers thread
   */
  def schedule(runnable: Runnable, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    log.trace(
      "Schedule scheduled event\n\trunnable = [%s]\n\tinitialDelay = [%s]\n\tdelay = [%s]\n\ttimeUnit = [%s]",
      runnable, initialDelay, delay, timeUnit)

    try {
      service.scheduleAtFixedRate(runnable,initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception => throw SchedulerException("Failed to schedule a Runnable", e)
    }
  }

  /**
   * Schedules to send the specified message to the receiver after delay
   */
  def scheduleOnce(receiver: ActorRef, message: AnyRef, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    log.trace(
      "Schedule one-time event\n\tevent = [%s]\n\treceiver = [%s]\n\tdelay = [%s]\n\ttimeUnit = [%s]",
      message, receiver, delay, timeUnit)
    try {
      service.schedule(
        new Runnable { def run = receiver ! message },
        delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception => throw SchedulerException( message + " could not be scheduleOnce'd on " + receiver, e)
    }
  }

  /**
   * Schedules a function to be run after delay,
   * avoid blocking operations since the runnable is executed in the schedulers thread
   */
  def scheduleOnce(f: () => Unit, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] =
    scheduleOnce(new Runnable { def run = f() }, delay, timeUnit)

  /**
   * Schedules a runnable to be run after delay,
   * avoid blocking operations since the runnable is executed in the schedulers thread
   */
  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): ScheduledFuture[AnyRef] = {
    log.trace(
      "Schedule one-time event\n\trunnable = [%s]\n\tdelay = [%s]\n\ttimeUnit = [%s]",
      runnable, delay, timeUnit)
    try {
      service.schedule(runnable,delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
    } catch {
      case e: Exception => throw SchedulerException("Failed to scheduleOnce a Runnable", e)
    }
  }

  def shutdown = {
    log.info("Shutting down Scheduler")
    service.shutdown
  }

  def restart = {
    log.info("Restarting Scheduler")
    shutdown
    service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)
  }
}

private object SchedulerThreadFactory extends ThreadFactory {
  private var count = 0
  val threadFactory = Executors.defaultThreadFactory()

  def newThread(r: Runnable): Thread = {
    val thread = threadFactory.newThread(r)
    thread.setName("akka:scheduler-" + count)
    thread.setDaemon(true)
    thread
  }
}
