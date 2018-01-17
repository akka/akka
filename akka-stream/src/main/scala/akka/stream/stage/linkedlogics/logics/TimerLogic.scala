/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.logics

import akka.stream.stage.linkedlogic.impl.slots.TimerSlot
import akka.stream.stage.linkedlogic.impl.{ SlotsCollector, TimerSlotsCollector }

import scala.concurrent.duration.FiniteDuration

/**
 * Logic of processing elements from the timer.
 */
abstract class TimerLogic private[linkedlogic] () extends EnteringLogic() {
  private var timerSlot: TimerSlot = null

  private[linkedlogic] def start(slots: TimerSlotsCollector): Unit = {
    timerSlot = makeSlot(slots)
    timerSlot.start()
    start(slots, timerSlot)
  }

  final def cancel(): Unit = {
    timerSlot.cancel()
  }

  private[linkedlogic] def makeSlot(slots: TimerSlotsCollector): TimerSlot

  protected[linkedlogic] def timerHandler(): Unit
}

/**
 * Once scheduled timer.
 * Time for the start of processing depends on the readiness of the links.
 * So, if the links are not ready to accept a new element, timer processing call may be delayed.
 *
 * @param delay minimal delay before timer processing started.
 */
abstract class OnceScheduledTimerLogic private[linkedlogic] (delay: FiniteDuration) extends TimerLogic() {
  override private[linkedlogic] final def makeSlot(slots: TimerSlotsCollector): TimerSlot = {
    val timerSlot = slots.addOnceScheduledTimerSlot(delay)
    timerSlot.setTimerHandler(() ⇒ {
      timerHandler()
      timerSlot.close()
    })
    timerSlot.setOnTerminateHandler(() ⇒ { slots.removeEnteringSlot(timerSlot.id) })
    timerSlot
  }
}

/**
 * Periodically executed timer.
 * Time for the start of processing depends on the readiness of the links.
 * So, if the links are not ready to accept a new element, timer processing call may be delayed.
 *
 * @param period minimal period between timer processing.
 * @param initialDelay initial delay before first timer call.
 */
abstract class PeriodicallyTimerLogic private[linkedlogic] (period: FiniteDuration, initialDelay: Option[FiniteDuration] = None) extends TimerLogic() {
  override private[linkedlogic] final def makeSlot(slots: TimerSlotsCollector): TimerSlot = {
    val timerSlot = slots.addPeriodicallyTimerSlot(period, initialDelay)
    timerSlot.setTimerHandler(timerHandler)
    timerSlot.setOnTerminateHandler(() ⇒ { slots.removeEnteringSlot(timerSlot.id) })
    timerSlot
  }
}
