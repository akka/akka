/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.impl.slots

/**
 * Logic slot for the timer.
 */
private[linkedlogic] final class TimerSlot(id: Int, scheduleTimer: () ⇒ Unit, cancelTimer: () ⇒ Unit) extends EnteringSlot(id) {
  private var timerHandler: () ⇒ Unit = null
  private var pending = false

  override protected def isPending(): Boolean = pending
  override protected def requestNext(): Unit = {}

  def setTimerHandler(timerHandler: () ⇒ Unit): Unit = {
    this.timerHandler = timerHandler
  }

  def start(): Unit = {
    scheduleTimer()
  }

  def cancel(): Unit = {
    cancelTimer()
    close()
  }

  def process(): Unit = {
    if (isReadyToProcess()) {
      timerHandler()
      pending = false
    } else {
      pending = true
    }
  }
}
