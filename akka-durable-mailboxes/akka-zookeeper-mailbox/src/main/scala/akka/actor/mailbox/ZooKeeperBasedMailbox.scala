/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import MailboxProtocol._

import akka.actor.LocalActorRef
import akka.dispatch._
import akka.config.Config._
import akka.event.EventHandler
import akka.util.Duration
import akka.cluster.zookeeper._
import akka.AkkaException

import org.I0Itec.zkclient.serialize._

class ZooKeeperBasedMailboxException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object ZooKeeperMailboxConfig {
  val zkServerAddresses = config.getString("akka.actor.mailbox.zookeeper.server-addresses", "localhost:2181")
  val sessionTimeout = Duration(config.getInt("akka.actor.mailbox.zookeeper.session-timeout", 60), TIME_UNIT).toMillis.toInt
  val connectionTimeout = Duration(config.getInt("akka.actor.mailbox.zookeeper.connection-timeout", 60), TIME_UNIT).toMillis.toInt
  val blockingQueue = config.getBool("akka.actor.mailbox.zookeeper.blocking-queue", true)

  val queueNode = "/queues"
  val queuePathTemplate = queueNode + "/%s"

  object serializer extends ZkSerializer {
    def serialize(data: AnyRef): Array[Byte] = data match {
      case d: DurableMailboxMessageProtocol ⇒ d.toByteArray
      case null                             ⇒ throw new ZooKeeperBasedMailboxException("Expected a DurableMailboxMessageProtocol message, was null")
      case _                                ⇒ throw new ZooKeeperBasedMailboxException("Expected a DurableMailboxMessageProtocol message, was [" + data.getClass + "]")
    }

    def deserialize(bytes: Array[Byte]): AnyRef = DurableMailboxMessageProtocol.parseFrom(bytes)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ZooKeeperBasedMailbox(val owner: LocalActorRef) extends DurableExecutableMailbox(owner) {
  import ZooKeeperMailboxConfig._

  private val zkClient = new AkkaZkClient(zkServerAddresses, sessionTimeout, connectionTimeout)
  private val queue = new ZooKeeperQueue[Array[Byte]](zkClient, queuePathTemplate.format(name), blockingQueue)

  def enqueue(durableMessage: MessageInvocation) = {
    EventHandler.debug(this,
      "\nENQUEUING message in zookeeper-based mailbox [%s]".format(durableMessage))
    queue.enqueue(serialize(durableMessage))
  }

  def dequeue: MessageInvocation = try {
    val messageInvocation = deserialize(queue.dequeue.asInstanceOf[Array[Byte]])
    EventHandler.debug(this,
      "\nDEQUEUING message in zookeeper-based mailbox [%s]".format(messageInvocation))
    messageInvocation
  } catch {
    case e: java.util.NoSuchElementException ⇒ null
    case e: InterruptedException             ⇒ null
    case e ⇒
      EventHandler.error(e, this, "Couldn't dequeue from ZooKeeper-based mailbox")
      throw e
  }

  def size: Int = queue.size

  def isEmpty: Boolean = queue.isEmpty

  def clear(): Boolean = try {
    queue.clear
    true
  } catch {
    case e ⇒ false
  }

  def close() = zkClient.close
}
