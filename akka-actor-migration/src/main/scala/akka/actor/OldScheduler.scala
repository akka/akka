/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit
import scala.util.Duration

/**
 * Migration replacement for `object akka.actor.Scheduler`.
 */
@deprecated("use ActorSystem.scheduler instead", "2.0")
object OldScheduler {

  /**
   * Schedules to send the specified message to the receiver after initialDelay and then repeated after delay
   */
  @deprecated("use ActorSystem.scheduler instead", "2.0")
  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable =
    GlobalActorSystem.scheduler.schedule(
      Duration(initialDelay, timeUnit),
      Duration(delay, timeUnit),
      receiver,
      message)

  /**
   * Schedules to run specified function to the receiver after initialDelay and then repeated after delay
   */
  @deprecated("use ActorSystem.scheduler instead", "2.0")
  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable =
    GlobalActorSystem.scheduler.schedule(
      Duration(initialDelay, timeUnit),
      Duration(delay, timeUnit),
      new Runnable { def run = f() })

  /**
   * Schedules to run specified runnable to the receiver after initialDelay and then repeated after delay.
   */
  @deprecated("use ActorSystem.scheduler instead", "2.0")
  def schedule(runnable: Runnable, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable =
    GlobalActorSystem.scheduler.schedule(
      Duration(initialDelay, timeUnit),
      Duration(delay, timeUnit),
      runnable)

  /**
   * Schedules to send the specified message to the receiver after delay
   */
  @deprecated("use ActorSystem.scheduler instead", "2.0")
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): Cancellable =
    GlobalActorSystem.scheduler.scheduleOnce(
      Duration(delay, timeUnit),
      receiver,
      message)

  /**
   * Schedules a function to be run after delay.
   */
  @deprecated("use ActorSystem.scheduler instead", "2.0")
  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): Cancellable =
    GlobalActorSystem.scheduler.scheduleOnce(
      Duration(delay, timeUnit),
      new Runnable { def run = f() })

  /**
   * Schedules a runnable to be run after delay,
   */
  @deprecated("use ActorSystem.scheduler instead", "2.0")
  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): Cancellable =
    GlobalActorSystem.scheduler.scheduleOnce(
      Duration(delay, timeUnit),
      runnable)

}

