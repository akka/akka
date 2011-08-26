/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import MailboxProtocol._

import akka.actor.LocalActorRef
import akka.dispatch._
import akka.config.Config._
import akka.util.Duration
import akka.AkkaException
import akka.event.EventHandler

import com.surftools.BeanstalkClient._
import com.surftools.BeanstalkClientImpl._

class BeanstalkBasedMailboxException(message: String) extends AkkaException(message) {}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BeanstalkBasedMailbox(val owner: LocalActorRef) extends DurableExecutableMailbox(owner) {

  val hostname = config.getString("akka.actor.mailbox.beanstalk.hostname", "0.0.0.0")
  val port = config.getInt("akka.actor.mailbox.beanstalk.port", 11300)
  val reconnectWindow = Duration(config.getInt("akka.actor.mailbox.beanstalk.reconnect-window", 5), TIME_UNIT).toSeconds.toInt
  val messageSubmitDelay = Duration(config.getInt("akka.actor.mailbox.beanstalk.message-submit-delay", 0), TIME_UNIT).toSeconds.toInt
  val messageSubmitTimeout = Duration(config.getInt("akka.actor.mailbox.beanstalk.message-submit-timeout", 5), TIME_UNIT).toSeconds.toInt
  val messageTimeToLive = Duration(config.getInt("akka.actor.mailbox.beanstalk.message-time-to-live", 120), TIME_UNIT).toSeconds.toInt

  private val queue = new ThreadLocal[Client] { override def initialValue = connect(name) }

  // ===== For MessageQueue =====

  def enqueue(durableMessage: MessageInvocation) = {
    Some(queue.get.put(65536, messageSubmitDelay, messageTimeToLive, serialize(durableMessage)).toInt)
    EventHandler.debug(this, "\nENQUEUING message in beanstalk-based mailbox [%s]".format(durableMessage))
  }

  def dequeue: MessageInvocation = try {
    val job = queue.get.reserve(null)
    if (job eq null) null: MessageInvocation
    else {
      val bytes = job.getData
      if (bytes ne null) {
        queue.get.delete(job.getJobId)
        val messageInvocation = deserialize(bytes)
        EventHandler.debug(this, "\nDEQUEUING message in beanstalk-based mailbox [%s]".format(messageInvocation))
        messageInvocation
      } else null: MessageInvocation
    }
  } catch {
    case e: Exception ⇒
      EventHandler.error(e, this, "Beanstalk connection error")
      reconnect(name)
      null: MessageInvocation
  }

  /**
   * Completely delete the queue.
   */
  def remove: Boolean = {
    try {
      queue.get.kick(100000)
      true
    } catch {
      case e: Exception ⇒ false
    }
  }

  def size: Int = {
    val item = queue.get.reserve(0)
    if (item eq null) 0 else 1
  }

  def isEmpty = size == 0

  private def connect(name: String): Client = {
    @volatile
    var connected = false
    var attempts = 0
    var client: Client = null
    while (!connected) {
      attempts += 1
      try {
        client = new ClientImpl(hostname, port)
        client.useTube(name)
        client.watch(name)
        connected = true
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, "Unable to connect to Beanstalk. Retrying in [%s] seconds: %s".format(reconnectWindow, e))
          try {
            Thread.sleep(1000 * reconnectWindow)
          } catch {
            case e: InterruptedException ⇒ {}
          }
      }
    }
    EventHandler.info(this, "Beanstalk-based mailbox connected to Beanstalkd server")
    client
  }

  private def reconnect(name: String): ThreadLocal[Client] = {
    new ThreadLocal[Client] { override def initialValue: Client = connect(name) }
  }
}
