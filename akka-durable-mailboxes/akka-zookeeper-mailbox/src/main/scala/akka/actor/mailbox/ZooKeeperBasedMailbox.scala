/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.AkkaException
import akka.actor.ActorContext
import akka.cluster.zookeeper.AkkaZkClient
import akka.dispatch.Envelope
import akka.event.Logging
import akka.cluster.zookeeper.ZooKeeperQueue
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config
import akka.util.NonFatal
import akka.config.ConfigurationException
import akka.dispatch.MessageQueue
import akka.actor.ActorSystem

class ZooKeeperBasedMailboxException(message: String) extends AkkaException(message)

class ZooKeeperBasedMailboxType(systemSettings: ActorSystem.Settings, config: Config) extends MailboxType {
  private val settings = new ZooKeeperBasedMailboxSettings(systemSettings, config)
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new ZooKeeperBasedMessageQueue(o, settings)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

class ZooKeeperBasedMessageQueue(_owner: ActorContext, val settings: ZooKeeperBasedMailboxSettings) extends DurableMessageQueue(_owner) with DurableMessageSerialization {

  val queueNode = "/queues"
  val queuePathTemplate = queueNode + "/%s"

  val log = Logging(system, "ZooKeeperBasedMessageQueue")

  private val zkClient = new AkkaZkClient(
    settings.ZkServerAddresses,
    settings.SessionTimeout,
    settings.ConnectionTimeout)
  private val queue = new ZooKeeperQueue[Array[Byte]](zkClient, queuePathTemplate.format(name), true)

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    queue.enqueue(serialize(envelope))
  }

  def dequeue: Envelope = try {
    deserialize(queue.dequeue.asInstanceOf[Array[Byte]])
  } catch {
    case e: java.util.NoSuchElementException ⇒ null
    case e: InterruptedException             ⇒ null
    case NonFatal(e) ⇒
      log.error(e, "Couldn't dequeue from ZooKeeper-based mailbox, due to: " + e.getMessage)
      throw e
  }

  def numberOfMessages: Int = queue.size

  def hasMessages: Boolean = !queue.isEmpty

  def clear(): Boolean = try {
    queue.clear
    true
  } catch {
    case e: Exception ⇒ false
  }

  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = {
    try {
      zkClient.close()
    } catch {
      case e: Exception ⇒ // ignore
    }
  }
}
