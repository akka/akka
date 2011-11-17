/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.LocalActorRef
import akka.util.Duration
import akka.AkkaException
import org.I0Itec.zkclient.serialize._
import akka.actor.ActorCell
import akka.cluster.zookeeper.AkkaZkClient
import akka.dispatch.Envelope
import akka.event.Logging
import akka.cluster.zookeeper.ZooKeeperQueue
import akka.actor.ActorRef

class ZooKeeperBasedMailboxException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ZooKeeperBasedMailbox(val owner: ActorCell) extends DurableMailbox(owner) with DurableMessageSerialization {

  val zkServerAddresses = system.settings.config.getString("akka.actor.mailbox.zookeeper.server-addresses", "localhost:2181")
  def defaultTimeUnit = system.settings.DefaultTimeUnit
  val sessionTimeout = Duration(system.settings.config.getInt("akka.actor.mailbox.zookeeper.session-timeout", 60), defaultTimeUnit).toMillis.toInt
  val connectionTimeout = Duration(system.settings.config.getInt("akka.actor.mailbox.zookeeper.connection-timeout", 60), defaultTimeUnit).toMillis.toInt
  val blockingQueue = system.settings.config.getBool("akka.actor.mailbox.zookeeper.blocking-queue", true)

  val queueNode = "/queues"
  val queuePathTemplate = queueNode + "/%s"

  val log = Logging(system, this)

  private val zkClient = new AkkaZkClient(zkServerAddresses, sessionTimeout, connectionTimeout)
  private val queue = new ZooKeeperQueue[Array[Byte]](zkClient, queuePathTemplate.format(name), blockingQueue)

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in zookeeper-based mailbox [%s]".format(envelope))
    queue.enqueue(serialize(envelope))
  }

  def dequeue: Envelope = try {
    val messageInvocation = deserialize(queue.dequeue.asInstanceOf[Array[Byte]])
    log.debug("DEQUEUING message in zookeeper-based mailbox [%s]".format(messageInvocation))
    messageInvocation
  } catch {
    case e: java.util.NoSuchElementException ⇒ null
    case e: InterruptedException             ⇒ null
    case e ⇒
      log.error(e, "Couldn't dequeue from ZooKeeper-based mailbox, due to: " + e.getMessage)
      throw e
  }

  def numberOfMessages: Int = queue.size

  def hasMessages: Boolean = !queue.isEmpty

  def clear(): Boolean = try {
    queue.clear
    true
  } catch {
    case e ⇒ false
  }

  override def cleanUp() {
    try {
      zkClient.close()
    } catch {
      case e: Exception ⇒ // ignore
    }
  }
}
