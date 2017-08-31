/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import scala.concurrent.duration.FiniteDuration
import akka.annotation.DoNotInherit
import akka.util.OptionVal

/**
 * Scala API: Mix in Timers into your Actor to get support for scheduled
 * `self` messages via [[TimerScheduler]].
 *
 * Timers are bound to the lifecycle of the actor that owns it,
 * and thus are cancelled automatically when it is restarted or stopped.
 */
trait Timers extends Actor {

  private val _timers = new TimerSchedulerImpl(context)

  /**
   * Start and cancel timers via the enclosed `TimerScheduler`.
   */
  final def timers: TimerScheduler = _timers

  override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    timers.cancelAll()
    super.aroundPreRestart(reason, message)
  }

  override protected[akka] def aroundPostStop(): Unit = {
    timers.cancelAll()
    super.aroundPostStop()
  }

  override protected[akka] def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    msg match {
      case timerMsg: TimerSchedulerImpl.TimerMsg ⇒
        _timers.interceptTimerMsg(timerMsg) match {
          case OptionVal.Some(m) ⇒ super.aroundReceive(receive, m)
          case OptionVal.None    ⇒ // discard
        }
      case _ ⇒
        super.aroundReceive(receive, msg)
    }
  }

}

/**
 * Java API: Support for scheduled `self` messages via [[TimerScheduler]].
 *
 * Timers are bound to the lifecycle of the actor that owns it,
 * and thus are cancelled automatically when it is restarted or stopped.
 */
abstract class AbstractActorWithTimers extends AbstractActor with Timers {
  /**
   * Start and cancel timers via the enclosed `TimerScheduler`.
   */
  final def getTimers: TimerScheduler = timers
}

/**
 * Support for scheduled `self` messages in an actor.
 * It is used by mixing in trait `Timers` in Scala or extending `AbstractActorWithTimers`
 * in Java.
 *
 * Timers are bound to the lifecycle of the actor that owns it,
 * and thus are cancelled automatically when it is restarted or stopped.
 *
 * `TimerScheduler` is not thread-safe, i.e. it must only be used within
 * the actor that owns it.
 */
@DoNotInherit abstract class TimerScheduler {

  /**
   * Start a periodic timer that will send `msg` to the `self` actor at
   * a fixed `interval`.
   *
   * Each timer has a key and if a new timer with same key is started
   * the previous is cancelled and it's guaranteed that a message from the
   * previous timer is not received, even though it might already be enqueued
   * in the mailbox when the new timer is started.
   */
  def startPeriodicTimer(key: Any, msg: Any, interval: FiniteDuration): Unit

  /**
   * Start a timer that will send `msg` once to the `self` actor after
   * the given `timeout`.
   *
   * Each timer has a key and if a new timer with same key is started
   * the previous is cancelled and it's guaranteed that a message from the
   * previous timer is not received, even though it might already be enqueued
   * in the mailbox when the new timer is started.
   */
  def startSingleTimer(key: Any, msg: Any, timeout: FiniteDuration): Unit

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
