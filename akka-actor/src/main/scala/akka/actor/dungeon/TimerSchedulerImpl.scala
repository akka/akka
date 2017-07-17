/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import scala.concurrent.duration.FiniteDuration

import akka.annotation.InternalApi
import akka.event.Logging
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TimerSchedulerImpl {
  final case class Timer(key: Any, msg: Any, repeat: Boolean, generation: Int, task: Cancellable)
  final case class TimerMsg(key: Any, generation: Int, owner: TimerSchedulerImpl)
    extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class TimerSchedulerImpl(ctx: ActorContext) extends TimerScheduler {
  import TimerSchedulerImpl._

  private val log = Logging(ctx.system, classOf[TimerScheduler])
  private var timers: Map[Any, Timer] = Map.empty
  private var timerGen = 0
  private def nextTimerGen(): Int = {
    timerGen += 1
    timerGen
  }

  override def startPeriodicTimer(key: Any, msg: Any, interval: FiniteDuration): Unit =
    startTimer(key, msg, interval, repeat = true)

  override def startSingleTimer(key: Any, msg: Any, timeout: FiniteDuration): Unit =
    startTimer(key, msg, timeout, repeat = false)

  private def startTimer(key: Any, msg: Any, timeout: FiniteDuration, repeat: Boolean): Unit = {
    timers.get(key) match {
      case Some(t) ⇒ cancelTimer(t)
      case None    ⇒
    }
    val nextGen = nextTimerGen()

    val timerMsg = TimerMsg(key, nextGen, this)
    val task =
      if (repeat)
        ctx.system.scheduler.schedule(timeout, timeout, ctx.self, timerMsg)(ctx.dispatcher)
      else
        ctx.system.scheduler.scheduleOnce(timeout, ctx.self, timerMsg)(ctx.dispatcher)

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

  private def cancelTimer(timer: Timer): Unit = {
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

  def interceptTimerMsg(timerMsg: TimerMsg): OptionVal[AnyRef] = {
    timers.get(timerMsg.key) match {
      case None ⇒
        // it was from canceled timer that was already enqueued in mailbox
        log.debug("Received timer [{}] that has been removed, discarding", timerMsg.key)
        OptionVal.None // message should be ignored
      case Some(t) ⇒
        if (timerMsg.owner ne this) {
          // after restart, it was from an old instance that was enqueued in mailbox before canceled
          log.debug("Received timer [{}] from old restarted instance, discarding", timerMsg.key)
          OptionVal.None // message should be ignored
        } else if (timerMsg.generation == t.generation) {
          // valid timer
          log.debug("Received timer [{}]", timerMsg.key)
          if (!t.repeat)
            timers -= t.key
          OptionVal.Some(t.msg.asInstanceOf[AnyRef])
        } else {
          // it was from an old timer that was enqueued in mailbox before canceled
          log.debug(
            "Received timer [{}] from from old generation [{}], expected generation [{}], discarding",
            timerMsg.key, timerMsg.generation, t.generation)
          OptionVal.None // message should be ignored
        }
    }
  }

}
