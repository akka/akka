/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.mailbox

import org.apache.commons.io.FileUtils
import akka.actor.ActorCell
import akka.config.Configuration
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef

object FileBasedMailbox {
  def queuePath(config: Configuration): String = {
    config.getString("akka.actor.mailbox.file-based.directory-path", "./_mb") // /var/spool/akka
  }
}

class FileBasedMailbox(val owner: ActorCell) extends DurableMailbox(owner) with DurableMessageSerialization {

  val log = Logging(app, this)

  val queuePath = FileBasedMailbox.queuePath(owner.app.config)

  private val queue = try {
    try { FileUtils.forceMkdir(new java.io.File(queuePath)) } catch { case e ⇒ {} }
    val queue = new filequeue.PersistentQueue(queuePath, name, owner.app.config, log)
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
    case e ⇒ false //review why catch Throwable? And swallow potential Errors?
  }

}
