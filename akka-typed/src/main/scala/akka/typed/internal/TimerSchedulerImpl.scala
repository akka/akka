/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package internal

import scala.concurrent.duration.FiniteDuration

import akka.actor.Cancellable
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.typed.ActorRef
import akka.typed.ActorRef.ActorRefOps
import akka.typed.javadsl
import akka.typed.scaladsl
import akka.typed.scaladsl.ActorContext
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TimerSchedulerImpl {
  final case class Timer[T](key: Any, msg: T, repeat: Boolean, generation: Int, task: Cancellable)
  final case class TimerMsg(key: Any, generation: Int, owner: AnyRef)

  def withTimers[T](factory: TimerSchedulerImpl[T] ⇒ Behavior[T]): Behavior[T] = {
    scaladsl.Actor.deferred[T] { ctx ⇒
      val timerScheduler = new TimerSchedulerImpl[T](ctx)
      val behavior = factory(timerScheduler)
      timerScheduler.intercept(behavior)
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class TimerSchedulerImpl[T](ctx: ActorContext[T])
  extends scaladsl.TimerScheduler[T] with javadsl.TimerScheduler[T] {
  import TimerSchedulerImpl._

  // FIXME change to a class specific logger, see issue #21219
  private val log = ctx.system.log
  private var timers: Map[Any, Timer[T]] = Map.empty
  private val timerGen = Iterator from 1

  override def startPeriodicTimer(key: Any, msg: T, interval: FiniteDuration): Unit =
    startTimer(key, msg, interval, repeat = true)

  override def startSingleTimer(key: Any, msg: T, timeout: FiniteDuration): Unit =
    startTimer(key, msg, timeout, repeat = false)

  private def startTimer(key: Any, msg: T, timeout: FiniteDuration, repeat: Boolean): Unit = {
    timers.get(key) match {
      case Some(t) ⇒ cancelTimer(t)
      case None    ⇒
    }
    val nextGen = timerGen.next()

    val timerMsg = TimerMsg(key, nextGen, this)
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
    log.debug("Start timer [{}] with generation [{}]", key, nextGen)
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
    log.debug("Cancel timer [{}] with generation [{}]", timer.key, timer.generation)
    timer.task.cancel()
    timers -= timer.key
  }

  override def cancelAll(): Unit = {
    log.debug("Cancel all timers")
    timers.valuesIterator.foreach { timer ⇒
      timer.task.cancel()
    }
    timers = Map.empty
  }

  private def interceptTimerMsg(ctx: ActorContext[TimerMsg], timerMsg: TimerMsg): T = {
    timers.get(timerMsg.key) match {
      case None ⇒
        // it was from canceled timer that was already enqueued in mailbox
        log.debug("Received timer [{}] that has been removed, discarding", timerMsg.key)
        null.asInstanceOf[T] // message should be ignored
      case Some(t) ⇒
        if (timerMsg.owner ne this) {
          // after restart, it was from an old instance that was enqueued in mailbox before canceled
          log.debug("Received timer [{}] from old restarted instance, discarding", timerMsg.key)
          null.asInstanceOf[T] // message should be ignored
        } else if (timerMsg.generation == t.generation) {
          // valid timer
          log.debug("Received timer [{}]", timerMsg.key)
          if (!t.repeat)
            timers -= t.key
          t.msg
        } else {
          // it was from an old timer that was enqueued in mailbox before canceled
          log.debug(
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
    BehaviorImpl.intercept[T, TimerMsg](
      beforeMessage = interceptTimerMsg,
      beforeSignal = (ctx, sig) ⇒ {
      sig match {
        case PreRestart | PostStop ⇒ cancelAll()
        case _                     ⇒ // unhandled
      }
      true
    },
      afterMessage = (ctx, msg, b) ⇒ b, // TODO optimize by using more ConstantFun
      afterSignal = (ctx, sig, b) ⇒ b,
      behavior)(ClassTag(classOf[TimerSchedulerImpl.TimerMsg]))
  }

}
