/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.impl.slots

import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.OverflowStrategy
import akka.stream.stage.OutHandler
import akka.stream.stage.linkedlogic.impl.slots.OutputSlot.BufferInfo

import scala.collection.mutable

/**
 * Logic slot for the outlet.
 */
private[linkedlogic] final class OutputSlot[Out](val id: Int, name: String, isAvailable: () ⇒ Boolean, push: (Out) ⇒ Unit,
                                                 notifyAvailable: (Int, Boolean) ⇒ Unit, completeStage: () ⇒ Unit)(implicit system: ActorSystem) {
  private case class Link(info: Option[BufferInfo]) {
    val queue = mutable.Queue.empty[(Long, Out)]
    var removed = false
  }

  private val log = Logging(system, getClass)
  private var links = Map.empty[Int, Link]
  private var availableLinks = Set.empty[Int]
  private var enqueueSequence: Long = 0L

  def link(enteringSlotId: Int, bufferInfo: Option[BufferInfo]): Unit = {
    if (links.get(enteringSlotId).isDefined) {
      sys.error(s"Link from the entering slot ${enteringSlotId} to the output slot ${id} is already defined")
    }
    links += (enteringSlotId -> Link(bufferInfo))
    notifyAvailable(enteringSlotId, true)
  }

  def unlink(enteringSlotId: Int): Unit = {
    for (link ← links.get(enteringSlotId)) {
      if (link.queue.isEmpty) {
        availableLinks -= enteringSlotId
        links -= enteringSlotId
      } else {
        link.removed = true
      }
    }
  }

  def push(enteringSlotId: Int, packet: Out): Unit = {
    val link = getLink(enteringSlotId)
    val available = if (isAvailable()) {
      assert(link.queue.isEmpty)
      push(packet)
      true
    } else {
      link.info match {
        case Some(info) ⇒
          if (info.overflowStrategy == OverflowStrategy.fail) {
            if (link.queue.size >= info.maxSize) {
              log.error(s"Output queue to ${name} overflowed. Current size is ${link.queue.size}. Complete stage.")
              completeStage()
              true
            } else {
              enqueue(enteringSlotId, packet)
              true
            }
          } else if (info.overflowStrategy == OverflowStrategy.backpressure) {
            enqueue(enteringSlotId, packet)
            if (link.queue.size >= info.maxSize) {
              val portion = if (info.maxSize != 0) info.maxSize else 10
              if (link.queue.size % portion == 0) {
                log.error(s"Output queue to ${name} is overflowed. Current size is ${link.queue.size}. Activate back pressure.")
              }
              false
            } else {
              true
            }
          } else if (info.overflowStrategy == OverflowStrategy.dropBuffer) {
            if (link.queue.size >= info.maxSize) {
              log.error(s"Output queue to ${name} overflowed. Clear it.")
              link.queue.clear()
            }
            enqueue(enteringSlotId, packet)
            true
          } else if (info.overflowStrategy == OverflowStrategy.dropHead) {
            while (link.queue.size >= info.maxSize) {
              link.queue.dequeue()
            }
            enqueue(enteringSlotId, packet)
            true
          } else if (info.overflowStrategy == OverflowStrategy.dropTail) {
            for (i ← 0 until link.queue.size) {
              val e = link.queue.dequeue()
              if (i != link.queue.size - 1) {
                link.queue.enqueue(e)
              }
            }
            enqueue(enteringSlotId, packet)
            true
          } else if (info.overflowStrategy == OverflowStrategy.dropNew) {
            true
          } else {
            sys.error(s"Invalid overflow strategy ${info.overflowStrategy}")
          }
        case None ⇒
          enqueue(enteringSlotId, packet)
          false
      }
    }
    if (!available) {
      notifyAvailable(enteringSlotId, false)
    }
  }

  def makeHandler() = {
    new OutHandler() {
      @throws[Exception](classOf[Exception])
      override def onPull(): Unit = {
        for (packet ← dequeue()) {
          push(packet)
        }
      }
    }
  }

  private def getLink(enteringSlotId: Int) = {
    links.get(enteringSlotId) match {
      case Some(link) ⇒ link
      case None       ⇒ sys.error(s"No link to entering slot ${enteringSlotId}")
    }
  }

  private def enqueue(enteringSlotId: Int, packet: Out): Unit = {
    val link = getLink(enteringSlotId)
    link.queue.enqueue((enqueueSequence, packet))
    enqueueSequence += 1
    if (link.queue.size == 1) {
      availableLinks += enteringSlotId
    }
    if (link.queue.size >= 100 && link.queue.size % 10 == 0) {
      log.warning(s"Output queue to ${name} size is ${link.queue.size}.")
    }
  }

  private def dequeue(): Option[Out] = {
    var frontIndex = 0
    var frontLink = Option.empty[Link]
    availableLinks.foreach {
      inboundIndex ⇒
        {
          val buffer = getLink(inboundIndex)
          if (frontLink.isEmpty || buffer.queue.front._1 < frontLink.get.queue.front._1) {
            frontIndex = inboundIndex
            frontLink = Some(buffer)
          }
        }
    }
    frontLink match {
      case Some(link) ⇒
        val out = link.queue.dequeue()
        if (link.queue.isEmpty) {
          availableLinks -= frontIndex
        }
        if (!link.removed) {
          link.info match {
            case None ⇒
              if (link.queue.isEmpty) {
                notifyAvailable(frontIndex, true)
              }
            case Some(info) if (info.overflowStrategy == OverflowStrategy.backpressure) ⇒
              if (info.maxSize == 0) {
                if (link.queue.isEmpty) {
                  notifyAvailable(frontIndex, true)
                }
              } else if (link.queue.size == info.maxSize - 1) {
                notifyAvailable(frontIndex, true)
              }
            case _ ⇒
          }
        } else if (link.queue.isEmpty) {
          links -= frontIndex
        }
        Some(out._2)
      case None ⇒
        None
    }
  }
}

object OutputSlot {
  case class BufferInfo(maxSize: Int, overflowStrategy: OverflowStrategy)
}
