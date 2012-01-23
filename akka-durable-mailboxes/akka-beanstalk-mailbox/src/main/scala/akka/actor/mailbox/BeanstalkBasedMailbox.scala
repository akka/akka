/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.surftools.BeanstalkClient._
import com.surftools.BeanstalkClientImpl._
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.util.Duration
import akka.AkkaException
import akka.actor.ActorContext
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config

class BeanstalkBasedMailboxException(message: String) extends AkkaException(message) {}

class BeanstalkBasedMailboxType(config: Config) extends MailboxType {
  override def create(owner: ActorContext) = new BeanstalkBasedMailbox(owner)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BeanstalkBasedMailbox(val owner: ActorContext) extends DurableMailbox(owner) with DurableMessageSerialization {

  private val settings = BeanstalkBasedMailboxExtension(owner.system)
  private val messageSubmitDelaySeconds = settings.MessageSubmitDelay.toSeconds.toInt
  private val messageTimeToLiveSeconds = settings.MessageTimeToLive.toSeconds.toInt

  val log = Logging(system, "BeanstalkBasedMailbox")

  private val queue = new ThreadLocal[Client] { override def initialValue = connect(name) }

  // ===== For MessageQueue =====

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in beanstalk-based mailbox [%s]".format(envelope))
    Some(queue.get.put(65536, messageSubmitDelaySeconds, messageTimeToLiveSeconds, serialize(envelope)).toInt)
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
        client = new ClientImpl(settings.Hostname, settings.Port)
        client.useTube(name)
        client.watch(name)
        connected = true
      } catch {
        case e: Exception ⇒
          log.error(e, "Unable to connect to Beanstalk. Retrying in [%s] seconds: %s".
            format(settings.ReconnectWindow.toSeconds, e))
          try {
            Thread.sleep(settings.ReconnectWindow.toMillis)
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
