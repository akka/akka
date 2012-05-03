/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.mailbox

import org.apache.commons.io.FileUtils
import akka.actor.ActorContext
import akka.dispatch.{ Envelope, MessageQueue }
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config
import akka.util.NonFatal
import akka.config.ConfigurationException
import akka.actor.ActorSystem

class FileBasedMailboxType(systemSettings: ActorSystem.Settings, config: Config) extends MailboxType {
  private val settings = new FileBasedMailboxSettings(systemSettings, config)
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new FileBasedMessageQueue(o, settings)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

class FileBasedMessageQueue(_owner: ActorContext, val settings: FileBasedMailboxSettings) extends DurableMessageQueue(_owner, settings) with DurableMessageSerialization {

  val log = Logging(system, "FileBasedMessageQueue")

  val queuePath = settings.QueuePath

  private val queue = try {
    try { FileUtils.forceMkdir(new java.io.File(queuePath)) } catch { case NonFatal(_) ⇒ {} }
    val queue = new filequeue.PersistentQueue(queuePath, name, settings, log)
    queue.setup // replays journal
    queue.discardExpired
    queue
  } catch {
    case e: Exception ⇒
      log.error(e, "Could not create a file-based mailbox")
      throw e
  }

  def enqueue(receiver: ActorRef, envelope: Envelope) = withCircuitBreaker {
    queue.add(serialize(envelope))
  }

  def dequeue(): Envelope = withCircuitBreaker {
    try {
      val item = queue.remove
      if (item.isDefined) {
        queue.confirmRemove(item.get.xid)
        deserialize(item.get.data)
      } else null
    } catch {
      case e: java.util.NoSuchElementException ⇒ null
      case e: Exception ⇒
        log.error(e, "Couldn't dequeue from file-based mailbox")
        throw e
    }
  }

  def numberOfMessages: Int = withCircuitBreaker {
    queue.length.toInt
  }

  def hasMessages: Boolean = numberOfMessages > 0

  /**
   * Completely delete the queue.
   */
  def remove: Boolean = try {
    queue.remove
    true
  } catch {
    case NonFatal(_) ⇒ false
  }

  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = ()

}
