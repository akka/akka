/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorContext
import akka.actor.Cancellable

/**
 * [[Transformer]] with support for scheduling keyed (named) timer events.
 */
abstract class TimerTransformer[-T, +U] extends Transformer[T, U] {
  import TimerTransformer._
  private val timers = mutable.Map[Any, Timer]()
  private val timerIdGen = Iterator from 1

  private var context: Option[ActorContext] = None
  // when scheduling before `start` we must queue the operations
  private var queued = List.empty[Queued]

  /**
   * INTERNAL API
   */
  private[akka] final def start(ctx: ActorContext): Unit = {
    context = Some(ctx)
    queued.reverse.foreach {
      case QueuedSchedule(timerKey, interval)  ⇒ schedulePeriodically(timerKey, interval)
      case QueuedScheduleOnce(timerKey, delay) ⇒ scheduleOnce(timerKey, delay)
      case QueuedCancelTimer(timerKey)         ⇒ cancelTimer(timerKey)
    }
    queued = Nil
  }

  /**
   * INTERNAL API
   */
  private[akka] final def stop(): Unit = {
    timers.foreach { case (_, Timer(_, task)) ⇒ task.cancel() }
    timers.clear()
  }

  /**
   * Schedule timer to call [[#onTimer]] periodically with the given interval.
   * Any existing timer with the same key will automatically be canceled before
   * adding the new timer.
   */
  def schedulePeriodically(timerKey: Any, interval: FiniteDuration): Unit =
    context match {
      case Some(ctx) ⇒
        cancelTimer(timerKey)
        val id = timerIdGen.next()
        val task = ctx.system.scheduler.schedule(interval, interval, ctx.self,
          Scheduled(timerKey, id, repeating = true))(ctx.dispatcher)
        timers(timerKey) = Timer(id, task)
      case None ⇒
        queued = QueuedSchedule(timerKey, interval) :: queued
    }

  /**
   * Schedule timer to call [[#onTimer]] after given delay.
   * Any existing timer with the same key will automatically be canceled before
   * adding the new timer.
   */
  def scheduleOnce(timerKey: Any, delay: FiniteDuration): Unit =
    context match {
      case Some(ctx) ⇒
        cancelTimer(timerKey)
        val id = timerIdGen.next()
        val task = ctx.system.scheduler.scheduleOnce(delay, ctx.self,
          Scheduled(timerKey, id, repeating = false))(ctx.dispatcher)
        timers(timerKey) = Timer(id, task)
      case None ⇒
        queued = QueuedScheduleOnce(timerKey, delay) :: queued
    }

  /**
   * Cancel timer, ensuring that the [[#onTimer]] is not subsequently called.
   * @param timerKey key of the timer to cancel
   */
  def cancelTimer(timerKey: Any): Unit =
    timers.get(timerKey).foreach { t ⇒
      t.task.cancel()
      timers -= timerKey
    }

  /**
   * Inquire whether the timer is still active. Returns true unless the
   * timer does not exist, has previously been canceled or if it was a
   * single-shot timer that was already triggered.
   */
  final def isTimerActive(timerKey: Any): Boolean = timers contains timerKey

  /**
   * INTERNAL API
   */
  private[akka] def onScheduled(scheduled: Scheduled): immutable.Seq[U] = {
    val Id = scheduled.timerId
    timers.get(scheduled.timerKey) match {
      case Some(Timer(Id, _)) ⇒
        if (!scheduled.repeating) timers -= scheduled.timerKey
        onTimer(scheduled.timerKey)
      case _ ⇒ Nil // already canceled, or re-scheduled
    }
  }

  /**
   * Will be called when the scheduled timer is triggered.
   * @param timerKey key of the scheduled timer
   */
  def onTimer(timerKey: Any): immutable.Seq[U]
}

/**
 * INTERNAL API
 */
private object TimerTransformer {
  case class Scheduled(timerKey: Any, timerId: Int, repeating: Boolean)

  sealed trait Queued
  case class QueuedSchedule(timerKey: Any, interval: FiniteDuration) extends Queued
  case class QueuedScheduleOnce(timerKey: Any, delay: FiniteDuration) extends Queued
  case class QueuedCancelTimer(timerKey: Any) extends Queued

  case class Timer(id: Int, task: Cancellable)

}

