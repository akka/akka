/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import scala.concurrent.duration.FiniteDuration

/**
 * Support for scheduled `self` messages in an actor.
 * It is used with `Behaviors.withTimers`.
 * Timers are bound to the lifecycle of the actor that owns it,
 * and thus are cancelled automatically when it is restarted or stopped.
 *
 * `TimerScheduler` is not thread-safe, i.e. it must only be used within
 * the actor that owns it.
 */
trait TimerScheduler[T] {

  /**
   * Start a periodic timer that will send `msg` to the `self` actor at
   * a fixed `interval`.
   *
   * Each timer has a key and if a new timer with same key is started
   * the previous is cancelled and it's guaranteed that a message from the
   * previous timer is not received, even though it might already be enqueued
   * in the mailbox when the new timer is started.
   */
  def startPeriodicTimer(key: Any, msg: T, interval: FiniteDuration): Unit

  /**
   * Start a timer that will send `msg` once to the `self` actor after
   * the given `timeout`.
   *
   * Each timer has a key and if a new timer with same key is started
   * the previous is cancelled and it's guaranteed that a message from the
   * previous timer is not received, even though it might already be enqueued
   * in the mailbox when the new timer is started.
   */
  def startSingleTimer(key: Any, msg: T, timeout: FiniteDuration): Unit

  /**
   * Check if a timer with a given `key` is active.
   */
  def isTimerActive(key: Any): Boolean

  /**
   * Cancel a timer with a given `key`.
   * If canceling a timer that was already canceled, or key never was used to start a timer
   * this operation will do nothing.
   *
   * It is guaranteed that a message from a canceled timer, including its previous incarnation
   * for the same key, will not be received by the actor, even though the message might already
   * be enqueued in the mailbox when cancel is called.
   */
  def cancel(key: Any): Unit

  /**
   * Cancel all timers.
   */
  def cancelAll(): Unit

}
