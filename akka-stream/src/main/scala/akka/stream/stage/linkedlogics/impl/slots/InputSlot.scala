/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.impl.slots

import akka.stream.stage.InHandler

/**
 * Logic slot for the inlet.
 */
private[linkedlogic] final class InputSlot[In](id: Int, isAvailable: () ⇒ Boolean, grab: () ⇒ In, tryPull: () ⇒ Unit) extends EnteringSlot(id) {
  private var inputHandler: (In) ⇒ Unit = null

  override protected def isPending(): Boolean = isAvailable()

  override protected def requestNext(): Unit = tryPull()

  def start(inputHandler: (In) ⇒ Unit): Unit = {
    this.inputHandler = inputHandler
    if (getLinks().isEmpty) {
      requestNext()
    }
  }

  def makeHandler() = new InHandler() {
    def onPush(): Unit = {
      if (isReadyToProcess()) {
        process()
      }
    }
  }

  def process(): Unit = {
    val packet = grab()
    inputHandler(packet)
    if (isReadyToProcess()) {
      requestNext()
    }
  }
}
