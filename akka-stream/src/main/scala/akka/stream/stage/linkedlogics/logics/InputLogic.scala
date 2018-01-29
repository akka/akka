/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.logics

import akka.stream.Inlet
import akka.stream.stage.linkedlogic.impl.SlotsCollector
import akka.stream.stage.linkedlogic.impl.slots.InputSlot

/**
 * Logic of processing elements from the the inlet.
 */
abstract class InputLogic[In] private[linkedlogic] (val inlet: Inlet[In]) extends EnteringLogic {
  private[linkedlogic] def inputHandler(data: In): Unit

  private[linkedlogic] def start(slots: SlotsCollector): Unit = {
    val inputSlot = slots.getInputSlot(inlet)
    inputSlot.start(inputHandler)
    start(slots, inputSlot)
  }
}
