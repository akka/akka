/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.surftools.BeanstalkClient._
import com.surftools.BeanstalkClientImpl._
import akka.actor.LocalActorRef
import akka.util.Duration
import akka.AkkaException
import akka.actor.ActorCell
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef

class BeanstalkBasedMailboxException(message: String) extends AkkaException(message) {}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BeanstalkBasedMailbox(val owner: ActorCell) extends DurableMailbox(owner) with DurableMessageSerialization {

  val hostname = system.settings.config.getString("akka.actor.mailbox.beanstalk.hostname")
  val port = system.settings.config.getInt("akka.actor.mailbox.beanstalk.port")
  def defaultTimeUnit = system.settings.DefaultTimeUnit
  val reconnectWindow = Duration(system.settings.config.getInt("akka.actor.mailbox.beanstalk.reconnect-window"), defaultTimeUnit).toSeconds.toInt
  val messageSubmitDelay = Duration(system.settings.config.getInt("akka.actor.mailbox.beanstalk.message-submit-delay"), defaultTimeUnit).toSeconds.toInt
  val messageSubmitTimeout = Duration(system.settings.config.getInt("akka.actor.mailbox.beanstalk.message-submit-timeout"), defaultTimeUnit).toSeconds.toInt
  val messageTimeToLive = Duration(system.settings.config.getInt("akka.actor.mailbox.beanstalk.message-time-to-live"), defaultTimeUnit).toSeconds.toInt

  val log = Logging(system, "BeanstalkBasedMailbox")

  private val queue = new ThreadLocal[Client] { override def initialValue = connect(name) }

  // ===== For MessageQueue =====

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in beanstalk-based mailbox [%s]".format(envelope))
    Some(queue.get.put(65536, messageSubmitDelay, messageTimeToLive, serialize(envelope)).toInt)
  }

  def dequeue(): Envelope = try {
    val job = queue.get.reserve(null)
    if (job eq null) null: Envelope
    else {
      val bytes = job.getData
      if (bytes ne null) {
        queue.get.delete(job.getJobId)
        val envelope = deserialize(bytes)
        log.debug("DEQUEUING message in beanstalk-based mailbox [%s]".format(envelope))
        envelope
      } else null: Envelope
    }
  } catch {
    case e: Exception ⇒
      log.error(e, "Beanstalk connection error, due to: " + e.getMessage)
      reconnect(name)
      null: Envelope
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

  def numberOfMessages: Int = {
    val item = queue.get.reserve(0)
    if (item eq null) 0 else 1
  }

  def hasMessages: Boolean = numberOfMessages > 0

  private def connect(name: String): Client = {
    // TODO PN: Why volatile on local variable?
    @volatile
    var connected = false
    // TODO PN: attempts is not used. Should we have maxAttempts check? Note that this is called from ThreadLocal.initialValue 
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
          log.error(e, "Unable to connect to Beanstalk. Retrying in [%s] seconds: %s".format(reconnectWindow, e))
          try {
            Thread.sleep(1000 * reconnectWindow)
          } catch {
            case e: InterruptedException ⇒ {}
          }
      }
    }
    log.info("Beanstalk-based mailbox connected to Beanstalkd server")
    client
  }

  // TODO PN What is the purpose of this?
  private def reconnect(name: String): ThreadLocal[Client] = {
    new ThreadLocal[Client] { override def initialValue: Client = connect(name) }
  }
}
