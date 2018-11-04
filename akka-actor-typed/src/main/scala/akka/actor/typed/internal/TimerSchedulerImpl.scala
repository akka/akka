/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import akka.actor.typed.ActorRef.ActorRefOps
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{ Cancellable, NotInfluenceReceiveTimeout, typed }
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.util.JavaDurationConverters._

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TimerSchedulerImpl {
  sealed trait TimerMsg[K] {
    def key: K
    def generation: Int
    def owner: AnyRef
  }

  final case class Timer[K, T](key: K, msg: T, repeat: Boolean, generation: Int, task: Cancellable)
  final case class InfluenceReceiveTimeoutTimerMsg[K](key: K, generation: Int, owner: AnyRef) extends TimerMsg[K]
  final case class NotInfluenceReceiveTimeoutTimerMsg[K](key: K, generation: Int, owner: AnyRef)
    extends TimerMsg[K] with NotInfluenceReceiveTimeout

  def withTimers[T](factory: TimerSchedulerImpl[Any, T] ⇒ Behavior[T]): Behavior[T] = {
    scaladsl.Behaviors.setup[T](wrapWithTimers(sharedScheduler, factory))
  }

  def withKeyTypedTimers[K, T](factory: TimerSchedulerImpl[K, T] ⇒ Behavior[T]): Behavior[T] = {
    scaladsl.Behaviors.setup[T](wrapWithTimers(new TimerSchedulerImpl[K, T](_), factory))
  }

  def sharedScheduler[T](ctx: ActorContext[T]): TimerSchedulerImpl[Any, T] = {
    ctx match {
      case ctxImpl: ActorContextImpl[T] ⇒ ctxImpl.timer
      case _                            ⇒ new TimerSchedulerImpl[Any, T](ctx)
    }
  }

  def wrapWithTimers[K, T](
    schedulerFactory: ActorContext[T] ⇒ TimerSchedulerImpl[K, T],
    behaviorFactory:  TimerSchedulerImpl[K, T] ⇒ Behavior[T]
  )(ctx: ActorContext[T]): Behavior[T] = {
    val timerScheduler = schedulerFactory(ctx)
    val behavior = behaviorFactory(timerScheduler)
    timerScheduler.intercept(behavior)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class TimerSchedulerImpl[K, T](ctx: ActorContext[T])
  extends scaladsl.TimerScheduler[K, T] with javadsl.TimerScheduler[K, T] {
  import TimerSchedulerImpl._

  private var timers: Map[K, Timer[K, T]] = Map.empty
  private val timerGen = Iterator from 1

  override def startPeriodicTimer(key: K, msg: T, interval: FiniteDuration): Unit =
    startTimer(key, msg, interval, repeat = true)

  override def startPeriodicTimer(key: K, msg: T, interval: java.time.Duration): Unit =
    startPeriodicTimer(key, msg, interval.asScala)

  override def startSingleTimer(key: K, msg: T, timeout: FiniteDuration): Unit =
    startTimer(key, msg, timeout, repeat = false)

  def startSingleTimer(key: K, msg: T, timeout: java.time.Duration): Unit =
    startSingleTimer(key, msg, timeout.asScala)

  private def startTimer(key: K, msg: T, timeout: FiniteDuration, repeat: Boolean): Unit = {
    timers.get(key) match {
      case Some(t) ⇒ cancelTimer(t)
      case None    ⇒
    }
    val nextGen = timerGen.next()

    val timerMsg =
      if (msg.isInstanceOf[NotInfluenceReceiveTimeout])
        NotInfluenceReceiveTimeoutTimerMsg(key, nextGen, this)
      else
        InfluenceReceiveTimeoutTimerMsg(key, nextGen, this)

    val task =
      if (repeat)
        ctx.system.scheduler.schedule(timeout, timeout) {
          ctx.self.upcast ! timerMsg
        }(ExecutionContexts.sameThreadExecutionContext)
      else
        ctx.system.scheduler.scheduleOnce(timeout) {
          ctx.self.upcast ! timerMsg
        }(ExecutionContexts.sameThreadExecutionContext)

    val nextTimer = Timer(key, msg, repeat, nextGen, task)
    ctx.log.debug("Start timer [{}] with generation [{}]", key, nextGen)
    timers = timers.updated(key, nextTimer)
  }

  override def isTimerActive(key: K): Boolean =
    timers.contains(key)

  override def cancel(key: K): Unit = {
    timers.get(key) match {
      case None    ⇒ // already removed/canceled
      case Some(t) ⇒ cancelTimer(t)
    }
  }

  private def cancelTimer(timer: Timer[K, T]): Unit = {
    ctx.log.debug("Cancel timer [{}] with generation [{}]", timer.key, timer.generation)
    timer.task.cancel()
    timers -= timer.key
  }

  override def cancelAll(): Unit = {
    ctx.log.debug("Cancel all timers")
    timers.valuesIterator.foreach { timer ⇒
      timer.task.cancel()
    }
    timers = Map.empty
  }

  def interceptTimerMsg(log: Logger, timerMsg: TimerMsg[K]): T = {
    timers.get(timerMsg.key) match {
      case None ⇒
        if (timerMsg.owner eq this) {
          // it was from canceled timer that was already enqueued in mailbox
          log.debug("Received timer [{}] that has been removed, discarding", timerMsg.key)
          null.asInstanceOf[T] // message should be ignored
        } else {
          // a valid timer, belonging to another scheduler, pass it on
          timerMsg.asInstanceOf[T]
        }
      case Some(t) ⇒
        if (timerMsg.owner ne this) {
          // after restart, it was from an old instance that was enqueued in mailbox before canceled
          log.debug("Received timer [{}] from old restarted instance, discarding", timerMsg.key)
          null.asInstanceOf[T] // message should be ignored
        } else if (timerMsg.generation == t.generation) {
          // valid timer
          if (!t.repeat)
            timers -= t.key
          t.msg
        } else {
          // it was from an old timer that was enqueued in mailbox before canceled
          ctx.log.debug(
            "Received timer [{}] from old generation [{}], expected generation [{}], discarding",
            timerMsg.key, timerMsg.generation, t.generation)
          null.asInstanceOf[T] // message should be ignored
        }
    }
  }

  def intercept(behavior: Behavior[T]): Behavior[T] = {
    // The scheduled TimerMsg is intercepted to guard against old messages enqueued
    // in mailbox before timer was canceled.
    // Intercept some signals to cancel timers when restarting and stopping.
    BehaviorImpl.intercept(new TimerInterceptor(this))(behavior)
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private final class TimerInterceptor[K, T](private val timerSchedulerImpl: TimerSchedulerImpl[K, T]) extends BehaviorInterceptor[T, T] {
  import TimerSchedulerImpl._
  import BehaviorInterceptor._

  override def aroundReceive(ctx: typed.ActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    val intercepted = msg match {
      case msg: TimerMsg[K] ⇒ timerSchedulerImpl.interceptTimerMsg(ctx.asScala.log, msg)
      case msg              ⇒ msg
    }

    // null means not applicable
    if (intercepted == null) Behavior.same
    else target(ctx, intercepted)
  }

  override def aroundSignal(ctx: typed.ActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    signal match {
      case PreRestart | PostStop ⇒ timerSchedulerImpl.cancelAll()
      case _                     ⇒ // unhandled
    }
    target(ctx, signal)
  }
}
