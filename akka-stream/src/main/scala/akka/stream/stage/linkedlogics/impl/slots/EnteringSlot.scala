/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.impl.slots

/**
 * Base class of logic slot for an inlet or a timer.
 */
private[linkedlogic] abstract class EnteringSlot(val id: Int) {
  private var links = Set.empty[Int]
  private var availableLinks = Set.empty[Int]
  private var onTerminateHandler = Option.empty[() ⇒ Unit]

  protected def isPending(): Boolean
  protected def process(): Unit
  protected def requestNext(): Unit

  def getLinks() = links

  def setOnTerminateHandler(onTerminateHandler: () ⇒ Unit): Unit = {
    this.onTerminateHandler = Some(onTerminateHandler)
  }

  def link(outputSlotId: Int): Unit = {
    if (links.contains(outputSlotId)) {
      sys.error(s"Link to the ${outputSlotId} already exists")
    }
    links += outputSlotId
  }

  def unlink(outputSlotId: Int): Unit = {
    links -= outputSlotId
    availableLinks -= outputSlotId
    handleIfReadyToProcess()
  }

  def notifyAvailable(outputSlotId: Int, available: Boolean): Unit = {
    if (available) {
      availableLinks += outputSlotId
      handleIfReadyToProcess()
    } else {
      availableLinks -= outputSlotId
    }
  }

  def isReadyToProcess(): Boolean = {
    links.size == availableLinks.size
  }

  def close(): Unit = {
    onTerminateHandler.foreach(_.apply())
  }

  private def handleIfReadyToProcess(): Unit = {
    if (isReadyToProcess()) {
      if (isPending()) {
        process()
      } else {
        requestNext()
      }
    }
  }
}
