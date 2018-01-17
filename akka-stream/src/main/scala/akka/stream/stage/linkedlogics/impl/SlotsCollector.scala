/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.impl

import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.stage.linkedlogic.impl.SlotsCollector.LogicInterface
import akka.stream.stage.linkedlogic.impl.TimerSlotsCollector.TimerLogicInterface
import akka.stream.stage.linkedlogic.impl.slots.{ EnteringSlot, InputSlot, OutputSlot, TimerSlot }
import akka.stream.stage.{ InHandler, OutHandler }
import akka.stream.{ Inlet, Outlet, Shape }

import scala.concurrent.duration.FiniteDuration

/**
 * Represents the collection of entering and output slots.
 * Slots for the inlets and outlets are created initially.
 */
private[linkedlogic] class SlotsCollector(val shape: Shape, logic: LogicInterface)(implicit system: ActorSystem) {
  private val log = Logging(system, getClass)

  protected var enteringSlotIdSequence = 0
  protected var outputSlotIdSequence = 0

  protected var enteringSlots: Map[Int, EnteringSlot] = makeEnteringSlots()
  protected var outputSlots: Map[Int, OutputSlot[_]] = makeOutputSlots()

  private def makeEnteringSlots() = {
    var inSlots = Map.empty[Int, EnteringSlot]
    for (index ← 0 until shape.getInlets.size()) {
      val inlet = shape.getInlets.get(index)
      val slot = new InputSlot[Any](enteringSlotIdSequence, () ⇒ logic.isAvailable(inlet), () ⇒ logic.grab(inlet),
        () ⇒ if (!logic.hasBeenPulled(inlet)) logic.tryPull(inlet))
      enteringSlotIdSequence += 1
      logic.setHandler(inlet, slot.makeHandler())
      inSlots += (slot.id -> slot)
    }
    inSlots
  }

  private def makeOutputSlots() = {
    var outSlots = Map.empty[Int, OutputSlot[_]]
    for (index ← 0 until shape.getOutlets.size()) {
      val outlet = shape.getOutlets.get(index).asInstanceOf[Outlet[Any]]
      val slot = new OutputSlot[Any](outputSlotIdSequence, outlet.toString(), () ⇒ logic.isAvailable(outlet), p ⇒ logic.push(outlet, p),
        (inboundIndex, available) ⇒ enteringSlots(inboundIndex).notifyAvailable(index, available), () ⇒ logic.completeStage())
      outputSlotIdSequence += 1
      logic.setHandler(outlet, slot.makeHandler())
      outSlots += (slot.id -> slot)
    }
    outSlots
  }

  def getEnteringSlot[In](slotId: Int) = enteringSlots.get(slotId)

  def getOutputSlot[Out](slotId: Int) = outputSlots.get(slotId)

  def getInputSlot[In](inlet: Inlet[In]) = {
    val index = shape.getInlets.indexOf(inlet)
    if (index == -1) {
      sys.error(s"No inlet ${inlet} in shape, existing ${shape.getInlets}")
    }
    enteringSlots.get(index) match {
      case Some(inputSlot: InputSlot[_]) ⇒
        inputSlot.asInstanceOf[InputSlot[In]]
      case Some(_) ⇒
        sys.error(s"Slot ${index} is not the input")
      case None ⇒
        sys.error(s"No input slot ${index}")
    }
  }

  def getOutputSlot[Out](outlet: Outlet[Out]) = {
    val index = shape.getOutlets.indexOf(outlet)
    if (index == -1) {
      sys.error(s"No outlet ${outlet} in shape, existing ${shape.getOutlets}")
    }
    outputSlots.get(index) match {
      case Some(outoutSlot) ⇒
        outputSlots(index).asInstanceOf[OutputSlot[Out]]
      case None ⇒
        sys.error(s"No output slot ${index}")
    }
  }

  def removeEnteringSlot(enteringSlotId: Int) = {
    for (timerSlot ← enteringSlots.get(enteringSlotId)) {
      for (outSlotId ← timerSlot.getLinks()) {
        for (outSlot ← getOutputSlot(outSlotId)) {
          outSlot.unlink(timerSlot.id)
        }
      }
      enteringSlots -= enteringSlotId
    }
  }
}

object SlotsCollector {
  trait LogicInterface {
    def setHandler(in: Inlet[_], handler: InHandler): Unit
    def isAvailable[T](in: Inlet[T]): Boolean
    def hasBeenPulled[T](in: Inlet[T]): Boolean
    def tryPull[T](in: Inlet[T]): Unit
    def grab[T](in: Inlet[T]): T
    def cancel[T](in: Inlet[T]): Unit
    def setHandler(out: Outlet[_], handler: OutHandler): Unit
    def isAvailable[T](out: Outlet[T]): Boolean
    def push[T](out: Outlet[T], elem: T): Unit
    def completeStage(): Unit
  }
}

/**
 * Represents the collection of input, output and timer slots.
 * Timer slots may be added and removed in progress.
 */
private[linkedlogic] class TimerSlotsCollector(shape: Shape, logic: TimerLogicInterface)(implicit system: ActorSystem) extends SlotsCollector(shape, logic) {
  private[linkedlogic] def addOnceScheduledTimerSlot(delay: FiniteDuration): TimerSlot = {
    var timerSlotId = enteringSlotIdSequence; enteringSlotIdSequence += 1
    val timerSlot = new TimerSlot(timerSlotId, () ⇒ logic.scheduleOnce(timerSlotId, delay), () ⇒ logic.cancelTimer(timerSlotId))
    enteringSlots += (timerSlot.id -> timerSlot)
    timerSlot
  }

  def addPeriodicallyTimerSlot(interval: FiniteDuration, initialDelay: Option[FiniteDuration] = None): TimerSlot = {
    var timerSlotId = enteringSlotIdSequence; enteringSlotIdSequence += 1
    val timerSlot = new TimerSlot(timerSlotId, () ⇒ initialDelay match {
      case Some(initialDelay) ⇒ logic.schedulePeriodicallyWithInitialDelay(timerSlotId, initialDelay, interval)
      case None               ⇒ logic.schedulePeriodically(timerSlotId, interval)
    }, () ⇒ logic.cancelTimer(timerSlotId))
    enteringSlots += (timerSlot.id -> timerSlot)
    timerSlot
  }

  def onTimer(timerKey: Any): Unit = {
    val timerSlotId = timerKey.asInstanceOf[Int]
    enteringSlots.get(timerSlotId) match {
      case Some(timerSlot: TimerSlot) ⇒
        timerSlot.process()
      case Some(_) ⇒
        sys.error(s"Slot ${timerSlotId} is not the timer")
      case None ⇒
    }
  }
}

object TimerSlotsCollector {
  trait TimerLogicInterface extends LogicInterface {
    def scheduleOnce(timerKey: Any, delay: FiniteDuration): Unit
    def schedulePeriodically(timerKey: Any, interval: FiniteDuration): Unit
    def schedulePeriodicallyWithInitialDelay(timerKey: Any, initialDelay: FiniteDuration, interval: FiniteDuration): Unit
    def cancelTimer(timerKey: Any): Unit
  }
}
