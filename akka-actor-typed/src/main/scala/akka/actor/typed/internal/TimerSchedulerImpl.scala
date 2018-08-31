/**
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
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TimerSchedulerImpl {
  sealed trait TimerMsg {
    def key: Any
    def generation: Int
    def owner: AnyRef
  }

  final case class Timer[T](key: Any, msg: T, repeat: Boolean, generation: Int, task: Cancellable)
  final case class InfluenceReceiveTimeoutTimerMsg(key: Any, generation: Int, owner: AnyRef) extends TimerMsg
  final case class NotInfluenceReceiveTimeoutTimerMsg(key: Any, generation: Int, owner: AnyRef)
    extends TimerMsg with NotInfluenceReceiveTimeout

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

  override def startSingleTimer(key: Any, msg: T, timeout: FiniteDuration): Unit =
    startTimer(key, msg, timeout, repeat = false)

  def startSingleTimer(key: Any, msg: T, timeout: java.time.Duration): Unit =
    startSingleTimer(key, msg, timeout.asScala)

  private def startTimer(key: Any, msg: T, timeout: FiniteDuration, repeat: Boolean): Unit = {
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

  def interceptTimerMsg(ctx: ActorContext[TimerMsg], timerMsg: TimerMsg): T = {
    timers.get(timerMsg.key) match {
      case None ⇒
        // it was from canceled timer that was already enqueued in mailbox
        ctx.log.debug("Received timer [{}] that has been removed, discarding", timerMsg.key)
        null.asInstanceOf[T] // message should be ignored
      case Some(t) ⇒
        if (timerMsg.owner ne this) {
          // after restart, it was from an old instance that was enqueued in mailbox before canceled
          ctx.log.debug("Received timer [{}] from old restarted instance, discarding", timerMsg.key)
          null.asInstanceOf[T] // message should be ignored
        } else if (timerMsg.generation == t.generation) {
          // valid timer
          if (!t.repeat)
            timers -= t.key
          t.msg
        } else {
          // it was from an old timer that was enqueued in mailbox before canceled
          ctx.log.debug(
            "Received timer [{}] from from old generation [{}], expected generation [{}], discarding",
            timerMsg.key, timerMsg.generation, t.generation)
          null.asInstanceOf[T] // message should be ignored
        }
    }
  }

  def intercept(behavior: Behavior[T]): Behavior[T] = {
    // The scheduled TimerMsg is intercepted to guard against old messages enqueued
    // in mailbox before timer was canceled.
    // Intercept some signals to cancel timers when when restarting and stopping.
    BehaviorImpl.intercept(new TimerInterceptor(this))(behavior).asInstanceOf[Behavior[T]]
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private final class TimerInterceptor[T](timerSchedulerImpl: TimerSchedulerImpl[T]) extends BehaviorInterceptor[AnyRef, T] {
  import TimerSchedulerImpl._

  override def aroundReceive(ctx: typed.ActorContext[AnyRef], msg: AnyRef, target: ReceiveTarget[T]): Behavior[T] = {
    val intercepted = msg match {
      case msg: TimerMsg ⇒ timerSchedulerImpl.interceptTimerMsg(ctx.asInstanceOf[ActorContext[TimerMsg]], msg)
      case msg           ⇒ msg.asInstanceOf[T]
    }

    // null means not applicable
    if (intercepted == null) Behavior.same
    else target.apply(intercepted)
  }

  override def aroundSignal(ctx: typed.ActorContext[AnyRef], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    signal match {
      case PreRestart | PostStop ⇒ timerSchedulerImpl.cancelAll()
      case _                     ⇒ // unhandled
    }
    target.apply(signal)
  }

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean =
    // only one timer interceptor per behavior stack is needed
    other.isInstanceOf[TimerInterceptor[_]]
}
