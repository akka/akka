/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.impl

import akka.stream.{ Inlet, Shape }
import akka.stream.stage.linkedlogic.logics.{ InputLogic, TimerLogic }

/**
 * Collector of input logics.
 * Input logics can be added initially or in progress.
 */
private[linkedlogic] class InputLogicsCollector {
  private var inputLogics = Map.empty[Inlet[_], InputLogic[_]]
  private var started = false

  def isStarted() = started

  def start(slots: SlotsCollector): Unit = {
    slots.shape.inlets.foreach(inlet ⇒ {
      if (!inputLogics.isDefinedAt(inlet)) {
        sys.error(s"Logic of inlet ${inlet} is not defined")
      }
    })
    inputLogics.values.foreach(_.start(slots))
    started = true
  }

  def addInputLogic[In](logic: InputLogic[In]): Unit = {
    if (started) {
      sys.error("Input logic can not be added after start")
    }
    if (inputLogics.isDefinedAt(logic.inlet)) {
      sys.error(s"Logic of ${logic.inlet} is already defined")
    }
    inputLogics += (logic.inlet -> logic)
    logic.setOnTerminateHandler(() ⇒ inputLogics -= logic.inlet)
  }
}

/**
 * Collector of input and timer logics.
 * Timer logics can be added initially or in progress.
 * It is possible also to remove timer logic.
 */
private[linkedlogic] final class TimerLogicsCollector extends InputLogicsCollector {
  private var timerLogics = Set.empty[TimerLogic]

  def start(slots: TimerSlotsCollector): Unit = {
    timerLogics.foreach(_.start(slots))
    super.start(slots)
  }

  def addTimerLogic[Logic <: TimerLogic](logic: Logic): Unit = {
    timerLogics += logic
    logic.setOnTerminateHandler(() ⇒ timerLogics -= logic)
  }
}
