/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.pubsub

import akka.actor.ActorRef

private[pubsub] trait PerGroupingBuffer {
  private type BufferedMessages = Vector[(Any, ActorRef)]

  private var buffers: Map[String, BufferedMessages] = Map.empty

  private var totalBufferSize = 0

  def bufferOr(grouping: String, message: Any, originalSender: ActorRef)(action: ⇒ Unit): Unit = {
    buffers.get(grouping) match {
      case None ⇒ action
      case Some(messages) ⇒
        buffers = buffers.updated(grouping, messages :+ ((message, originalSender)))
        totalBufferSize += 1
    }
  }

  def recreateAndForwardMessagesIfNeeded(grouping: String, recipient: ⇒ ActorRef): Unit = {
    buffers.get(grouping).filter(_.nonEmpty).foreach { messages ⇒
      forwardMessages(messages, recipient)
      totalBufferSize -= messages.length
    }
    buffers -= grouping
  }

  def forwardMessages(grouping: String, recipient: ActorRef): Unit = {
    buffers.get(grouping).foreach { messages ⇒
      forwardMessages(messages, recipient)
      totalBufferSize -= messages.length
    }
    buffers -= grouping
  }

  private def forwardMessages(messages: BufferedMessages, recipient: ActorRef): Unit = {
    messages.foreach {
      case (message, originalSender) ⇒ recipient.tell(message, originalSender)
    }
  }

  def initializeGrouping(grouping: String): Unit = buffers += grouping -> Vector.empty
}
