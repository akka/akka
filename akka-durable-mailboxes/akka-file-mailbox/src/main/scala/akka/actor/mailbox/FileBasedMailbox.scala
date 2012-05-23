/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.mailbox

import akka.actor.ActorContext
import akka.event.Logging
import akka.actor.ActorRef
import com.typesafe.config.Config
import akka.config.ConfigurationException
import akka.actor.ActorSystem
import akka.pattern.CircuitBreaker
import akka.dispatch._
import akka.util.{ Duration, NonFatal }

class FileBasedMailboxType(systemSettings: ActorSystem.Settings, config: Config) extends MailboxType {
  private val settings = new FileBasedMailboxSettings(systemSettings, config)
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new FileBasedMessageQueue(o, settings)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

class FileBasedMessageQueue(_owner: ActorContext, val settings: FileBasedMailboxSettings) extends DurableMessageQueue(_owner) with DurableMessageSerialization {

  val breaker = CircuitBreaker(_owner.system.scheduler, settings.CircuitBreakerMaxFailures, settings.CircuitBreakerCallTimeout, settings.CircuitBreakerResetTimeout)

  val log = Logging(system, "FileBasedMessageQueue")

  val queuePath = settings.QueuePath

  private val queue = try {
    (new java.io.File(queuePath)) match {
      case dir if dir.exists && !dir.isDirectory ⇒ throw new IllegalStateException("Path already occupied by non-directory " + dir)
      case dir if !dir.exists                    ⇒ if (!dir.mkdirs() && !dir.isDirectory) throw new IllegalStateException("Creation of directory failed " + dir)
      case _                                     ⇒ //All good
    }
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
    breaker.withSyncCircuitBreaker(queue.add(serialize(envelope)))
  }

  def dequeue(): Envelope = {
    breaker.withSyncCircuitBreaker(
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
      })
  }

  def numberOfMessages: Int = {
    breaker.withSyncCircuitBreaker(queue.length.toInt)
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
