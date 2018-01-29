/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.logics

import akka.stream.{ Outlet, OverflowStrategy }
import akka.stream.stage.linkedlogic.impl.SlotsCollector
import akka.stream.stage.linkedlogic.impl.slots.OutputSlot.BufferInfo
import akka.stream.stage.linkedlogic.impl.slots.{ EnteringSlot, OutputSlot }

/**
 * Defines links from an inlet or a timer to the outlets.
 */
abstract class EnteringLogic private[linkedlogic] {
  case class DefferedLinks(var outputs: Map[Outlet[_], BufferInfo] = Map.empty, var stopper: Boolean = false)
  private var deferredLinks = DefferedLinks()
  private var slots: SlotsCollector = null
  private var enteringSlot: EnteringSlot = null
  private var callbacks = Map.empty[Outlet[_], (_) ⇒ Unit]
  private var onTerminateHandler = Option.empty[() ⇒ Unit]

  /**
   * Links to outlet.
   * OutputLink is considered ready for processing element when no data in the buffer and outlet available to push.
   *
   * @param outlet - outlet to link with.
   */
  final def linkOutput[Out](outlet: Outlet[Out]): OutputLink[Out] = {
    linkOutput(outlet, 0, OverflowStrategy.backpressure)
  }

  /**
   * Links to the outlet.
   * OutputLink is considered ready for processing element when overflow strategy allows to append new element to the buffer.
   *
   * @param outlet outlet to link with.
   * @param bufferSize size of the buffer to control pulling elements from the input or processing events from the timer.
   * @param overflowStrategy strategy of back-pressure when the buffer is overflowed.
   */
  final def linkOutput[Out](outlet: Outlet[Out], bufferSize: Int, overflowStrategy: OverflowStrategy): OutputLink[Out] = {
    linkOutput(outlet, BufferInfo(bufferSize, overflowStrategy))
  }

  /**
   * Links array of the outlets.
   * OutputLinks are considered ready for processing element when no data in the all the buffers and all the outlets available to push.
   *
   * @param outlets - outlets to link with.
   */
  final def linkOutputs[Out](outlets: Seq[Outlet[Out]]): Seq[OutputLink[Out]] = {
    outlets.map(linkOutput(_, BufferInfo(0, OverflowStrategy.backpressure)))
  }

  /**
   * Links array of the outlets.
   * OutputLinks are considered ready for processing the element when overflow strategy allows to append new element to the buffers.
   *
   * @param outlets - outlets to link with.
   * @param bufferSize size of buffer to control pulling elements from the the input or processing events from the timer.
   * @param overflowStrategy strategy of back-pressure when the buffer is overflowed.
   */
  final def linkOutputs[Out](outlets: Seq[Outlet[Out]], bufferSize: Int, overflowStrategy: OverflowStrategy): Seq[OutputLink[Out]] = {
    outlets.map(linkOutput(_, BufferInfo(bufferSize, overflowStrategy)))
  }

  final def linkStopper(): StopperLink = {
    if (deferredLinks != null) {
      deferredLinks.stopper = true
    } else {
      startStopper()
    }
    new StopperLink() {
      override def remove(): Unit = {
        unlinkStopper()
      }
    }
  }

  private[linkedlogic] def start(slots: SlotsCollector, enteringSlot: EnteringSlot): Unit = {
    this.slots = slots
    this.enteringSlot = enteringSlot
    deferredLinks.outputs.foreach {
      case (outlet, bufferInfo) ⇒
        startOutput(outlet, bufferInfo)
    }
    if (deferredLinks.stopper) {
      startStopper()
    }
    deferredLinks = null
  }

  private[linkedlogic] def stop(): Unit = {
    onTerminateHandler.foreach(_.apply())
  }

  private[linkedlogic] def setOnTerminateHandler(onTerminateHandler: () ⇒ Unit): Unit = {
    this.onTerminateHandler = Some(onTerminateHandler)
  }

  private def getCallback[Out](outlet: Outlet[Out]): (Out) ⇒ Unit = {
    callbacks.get(outlet).get.asInstanceOf[(Out) ⇒ Unit]
  }

  private def linkOutput[Out](outlet: Outlet[Out], buffer: BufferInfo): OutputLink[Out] = {
    if (deferredLinks != null) {
      deferredLinks.outputs += (outlet -> buffer)
    } else {
      startOutput(outlet, buffer)
    }
    new OutputLink[Out]() {
      override def push(element: Out): Unit = {
        getCallback(outlet).apply(element)
      }

      override def remove(): Unit = {
        unlinkOutput(outlet)
      }
    }
  }

  private def startOutput[Out](outlet: Outlet[Out], bufferInfo: BufferInfo): Unit = {
    val outputSlot = slots.getOutputSlot(outlet)
    enteringSlot.link(outputSlot.id)
    outputSlot.link(enteringSlot.id, Some(bufferInfo))
    def push = (packet: Out) ⇒ outputSlot.asInstanceOf[OutputSlot[Out]].push(enteringSlot.id, packet)
    callbacks += (outlet -> push)
  }

  private def startStopper(): Unit = {
    enteringSlot.link(-1)
    enteringSlot.notifyAvailable(-1, false)
  }

  private def unlinkOutput[Out](outlet: Outlet[Out]): Unit = {
    if (deferredLinks != null) {
      deferredLinks.outputs -= outlet
    } else {
      val outputSlot = slots.getOutputSlot(outlet)
      enteringSlot.unlink(outputSlot.id)
      outputSlot.unlink(enteringSlot.id)
    }
  }

  private def unlinkStopper(): Unit = {
    if (deferredLinks != null) {
      deferredLinks.stopper = false
    } else {
      enteringSlot.unlink(-1)
    }
  }
}
