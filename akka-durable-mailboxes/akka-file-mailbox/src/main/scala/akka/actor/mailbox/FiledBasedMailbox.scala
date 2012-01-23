/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.mailbox

import org.apache.commons.io.FileUtils
import akka.actor.ActorContext
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config

class FileBasedMailboxType(config: Config) extends MailboxType {
  override def create(owner: ActorContext) = new FileBasedMailbox(owner)
}

class FileBasedMailbox(val owner: ActorContext) extends DurableMailbox(owner) with DurableMessageSerialization {

  val log = Logging(system, "FileBasedMailbox")

  private val settings = FileBasedMailboxExtension(owner.system)
  val queuePath = settings.QueuePath

  private val queue = try {
    try { FileUtils.forceMkdir(new java.io.File(queuePath)) } catch { case e ⇒ {} }
    val queue = new filequeue.PersistentQueue(queuePath, name, settings, log)
    queue.setup // replays journal
    queue.discardExpired
    queue
  } catch {
    case e: Exception ⇒
      log.error(e, "Could not create a file-based mailbox")
      throw e
  }

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in file-based mailbox [{}]", envelope)
    queue.add(serialize(envelope))
  }

  def dequeue(): Envelope = try {
    val item = queue.remove
    if (item.isDefined) {
      queue.confirmRemove(item.get.xid)
      val envelope = deserialize(item.get.data)
      log.debug("DEQUEUING message in file-based mailbox [{}]", envelope)
      envelope
    } else null
  } catch {
    case e: java.util.NoSuchElementException ⇒ null
    case e: Exception ⇒
      log.error(e, "Couldn't dequeue from file-based mailbox")
      throw e
  }

  def numberOfMessages: Int = {
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
    // TODO catching all and continue isn't good for OOME, ticket #1418
    case e ⇒ false
  }

}
