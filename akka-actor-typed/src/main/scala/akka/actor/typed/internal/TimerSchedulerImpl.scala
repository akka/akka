/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import akka.actor.typed.ActorRef.ActorRefOps
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{ Cancellable, NotInfluenceReceiveTimeout, typed }
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.util.JavaDurationConverters._
import akka.util.OptionVal

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TimerSchedulerImpl {
  final case class Timer[T](key: Any, msg: T, repeat: Boolean, generation: Int, task: Cancellable)
  sealed class TimerMsg(val key: Any, val generation: Int, val owner: AnyRef) {
    override def toString = s"TimerMsg(key=$key, generation=$generation, owner=$owner)"
  }

  def withTimers[T](factory: TimerSchedulerImpl[T] ⇒ Behavior[T]): Behavior[T] = {
    scaladsl.Behaviors.setup[T](wrapWithTimers(factory))
  }

  def wrapWithTimers[T](factory: TimerSchedulerImpl[T] ⇒ Behavior[T])(ctx: ActorContext[T]): Behavior[T] =
    ctx match {
      case ctxImpl: ActorContextImpl[T] ⇒
        val timerScheduler = ctxImpl.timer
        val behavior = factory(timerScheduler)
        timerScheduler.intercept(behavior)
      case _ ⇒ throw new IllegalArgumentException(s"timers not supported with [${ctx.getClass}]")
    }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class TimerSchedulerImpl[T](ctx: ActorContext[T])
  extends scaladsl.TimerScheduler[T] with javadsl.TimerScheduler[T] {
  import TimerSchedulerImpl._

  private var timers: Map[Any, Timer[T]] = Map.empty
  private val timerGen = Iterator from 1

  override def startPeriodicTimer(key: Any, msg: T, interval: FiniteDuration): Unit =
    startTimer(key, msg, interval, repeat = true)

  override def startPeriodicTimer(key: Any, msg: T, interval: java.time.Duration): Unit =
    startPeriodicTimer(key, msg, interval.asScala)

  override def startSingleTimer(key: Any, msg: T, delay: FiniteDuration): Unit =
    startTimer(key, msg, delay, repeat = false)

  def startSingleTimer(key: Any, msg: T, delay: java.time.Duration): Unit =
    startSingleTimer(key, msg, delay.asScala)

  private def startTimer(key: Any, msg: T, delay: FiniteDuration, repeat: Boolean): Unit = {
    timers.get(key) match {
      case Some(t) ⇒ cancelTimer(t)
      case None    ⇒
    }
    val nextGen = timerGen.next()

    val timerMsg =
      if (msg.isInstanceOf[NotInfluenceReceiveTimeout])
        new TimerMsg(key, nextGen, this) with NotInfluenceReceiveTimeout
      else
        new TimerMsg(key, nextGen, this)

    val task =
      if (repeat)
        ctx.system.scheduler.schedule(delay, delay) {
          ctx.self.unsafeUpcast ! timerMsg
        }(ExecutionContexts.sameThreadExecutionContext)
      else
        ctx.system.scheduler.scheduleOnce(delay) {
          ctx.self.unsafeUpcast ! timerMsg
        }(ExecutionContexts.sameThreadExecutionContext)

    val nextTimer = Timer(key, msg, repeat, nextGen, task)
    ctx.log.debug("Start timer [{}] with generation [{}]", key, nextGen)
    timers = timers.updated(key, nextTimer)
  }

  override def isTimerActive(key: Any): Boolean =
    timers.contains(key)

  override def cancel(key: Any): Unit = {
    timers.get(key) match {
      case None    ⇒ // already removed/canceled
      case Some(t) ⇒ cancelTimer(t)
    }
  }

  private def cancelTimer(timer: Timer[T]): Unit = {
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

  def interceptTimerMsg(log: Logger, timerMsg: TimerMsg): OptionVal[T] = {
    timers.get(timerMsg.key) match {
      case None ⇒
        // it was from canceled timer that was already enqueued in mailbox
        log.debug("Received timer [{}] that has been removed, discarding", timerMsg.key)
        OptionVal.none // message should be ignored
      case Some(t) ⇒
        if (timerMsg.owner ne this) {
          // after restart, it was from an old instance that was enqueued in mailbox before canceled
          log.debug("Received timer [{}] from old restarted instance, discarding", timerMsg.key)
          OptionVal.none // message should be ignored
        } else if (timerMsg.generation == t.generation) {
          // valid timer
          if (!t.repeat)
            timers -= t.key
          OptionVal.Some(t.msg)
        } else {
          // it was from an old timer that was enqueued in mailbox before canceled
          log.debug(
            "Received timer [{}] from old generation [{}], expected generation [{}], discarding",
            timerMsg.key, timerMsg.generation, t.generation)
          OptionVal.none // message should be ignored
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
private final class TimerInterceptor[T](timerSchedulerImpl: TimerSchedulerImpl[T]) extends BehaviorInterceptor[T, T] {
  import TimerSchedulerImpl._
  import BehaviorInterceptor._

  override def aroundReceive(ctx: typed.TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    val maybeIntercepted = msg match {
      case msg: TimerMsg ⇒ timerSchedulerImpl.interceptTimerMsg(ctx.asScala.log, msg)
      case msg           ⇒ OptionVal.Some(msg)
    }

    maybeIntercepted match {
      case OptionVal.None              ⇒ Behavior.same // None means not applicable
      case OptionVal.Some(intercepted) ⇒ target(ctx, intercepted)
    }
  }

  override def aroundSignal(ctx: typed.TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    signal match {
      case PreRestart | PostStop ⇒ timerSchedulerImpl.cancelAll()
      case _                     ⇒ // unhandled
    }
    target(ctx, signal)
  }

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean =
    // only one timer interceptor per behavior stack is needed
    other.isInstanceOf[TimerInterceptor[_]]
}
