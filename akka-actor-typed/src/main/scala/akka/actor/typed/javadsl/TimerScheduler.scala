/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl

import java.time.Duration

/**
 * Support for scheduled `self` messages in an actor.
 * It is used with `Behaviors.withTimers`, which also takes care of the
 * lifecycle of the timers such as cancelling them when the actor
 * is restarted or stopped.
 *
 * `TimerScheduler` is not thread-safe, i.e. it must only be used within
 * the actor that owns it.
 */
trait KeyTypedTimerScheduler[K, T] {

  /**
   * Start a periodic timer that will send `msg` to the `self` actor at
   * a fixed `interval`.
   *
   * Each timer has a key and if a new timer with same key is started
   * the previous is cancelled and it's guaranteed that a message from the
   * previous timer is not received, even though it might already be enqueued
   * in the mailbox when the new timer is started.
   */
  def startPeriodicTimer(key: K, msg: T, interval: Duration): Unit

  /**
   * * Start a timer that will send `msg` once to the `self` actor after
   * the given `timeout`.
   *
   * Each timer has a key and if a new timer with same key is started
   * the previous is cancelled and it's guaranteed that a message from the
   * previous timer is not received, even though it might already be enqueued
   * in the mailbox when the new timer is started.
   */
  def startSingleTimer(key: K, msg: T, timeout: Duration): Unit

  /**
   * Check if a timer with a given `key` is active.
   */
  def isTimerActive(key: K): Boolean

  /**
   * Cancel a timer with a given `key`.
   * If canceling a timer that was already canceled, or key never was used to start a timer
   * this operation will do nothing.
   *
   * It is guaranteed that a message from a canceled timer, including its previous incarnation
   * for the same key, will not be received by the actor, even though the message might already
   * be enqueued in the mailbox when cancel is called.
   */
  def cancel(key: K): Unit

  /**
   * Cancel all timers.
   */
  def cancelAll(): Unit

}

trait TimerScheduler[T] extends KeyTypedTimerScheduler[Any, T] {

  def withKeyType[K]: KeyTypedTimerScheduler[K, T] = this.asInstanceOf[KeyTypedTimerScheduler[K, T]]

}
