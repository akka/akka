/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.mailbox

import akka.actor.LocalActorRef
import akka.dispatch._
import akka.config.Config._
import akka.event.EventHandler

import org.apache.commons.io.FileUtils

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object FileBasedMailboxUtil {
  val queuePath = config.getString("akka.actor.mailbox.file-based.directory-path", "./_mb") // /var/spool/akka
}

class FileBasedMailbox(val owner: LocalActorRef) extends DurableExecutableMailbox(owner) {
  import FileBasedMailboxUtil._

  private val queue = try {
    try { FileUtils.forceMkdir(new java.io.File(queuePath)) } catch { case e ⇒ {} }
    val queue = new filequeue.PersistentQueue(queuePath, name, config)
    queue.setup // replays journal
    queue.discardExpired
    queue
  } catch {
    case e: Exception ⇒
      EventHandler.error(e, this, "Could not create a file-based mailbox")
      throw e
  }

  def enqueue(message: MessageInvocation) = {
    EventHandler.debug(this, "\nENQUEUING message in file-based mailbox [%s]".format(message))
    queue.add(serialize(message))
  }

  def dequeue: MessageInvocation = try {
    val item = queue.remove
    if (item.isDefined) {
      queue.confirmRemove(item.get.xid)
      val messageInvocation = deserialize(item.get.data)
      EventHandler.debug(this, "\nDEQUEUING message in file-based mailbox [%s]".format(messageInvocation))
      messageInvocation
    } else null
  } catch {
    case e: java.util.NoSuchElementException ⇒ null
    case e: Exception ⇒
      EventHandler.error(e, this, "Couldn't dequeue from file-based mailbox")
      throw e
  }

  /**
   * Completely delete the queue.
   */
  def remove: Boolean = try {
    queue.remove
    true
  } catch {
    case e ⇒ false //review why catch Throwable? And swallow potential Errors?
  }

  def size: Int = queue.length.toInt

  def isEmpty: Boolean = size == 0
}
