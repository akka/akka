/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.mailbox

import akka.actor.ActorContext
import akka.dispatch.{ Envelope, MessageQueue }
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config
import akka.util.NonFatal
import akka.ConfigurationException
import akka.actor.ActorSystem

class FileBasedMailboxType(systemSettings: ActorSystem.Settings, config: Config) extends MailboxType {
  private val settings = new FileBasedMailboxSettings(systemSettings, config)
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new FileBasedMessageQueue(o, settings)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

class FileBasedMessageQueue(_owner: ActorContext, val settings: FileBasedMailboxSettings) extends DurableMessageQueue(_owner) with DurableMessageSerialization {
  // TODO Is it reasonable for all FileBasedMailboxes to have their own logger?
  private val log = Logging(system, "FileBasedMessageQueue")

  private val queue = try {
    (new java.io.File(settings.QueuePath)) match {
      case dir if dir.exists && !dir.isDirectory ⇒ throw new IllegalStateException("Path already occupied by non-directory " + dir)
      case dir if !dir.exists                    ⇒ if (!dir.mkdirs() && !dir.isDirectory) throw new IllegalStateException("Creation of directory failed " + dir)
      case _                                     ⇒ //All good
    }
    val queue = new filequeue.PersistentQueue(settings.QueuePath, name, settings, log)
    queue.setup // replays journal
    queue.discardExpired
    queue
  } catch {
    case NonFatal(e) ⇒
      log.error(e, "Could not create a file-based mailbox")
      throw e
  }

  def enqueue(receiver: ActorRef, envelope: Envelope): Unit = queue.add(serialize(envelope))

  def dequeue(): Envelope = try {
    queue.remove.map(item ⇒ { queue.confirmRemove(item.xid); deserialize(item.data) }).orNull
  } catch {
    case _: java.util.NoSuchElementException ⇒ null
    case NonFatal(e) ⇒
      log.error(e, "Couldn't dequeue from file-based mailbox")
      throw e
  }

  def numberOfMessages: Int = queue.length.toInt

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
