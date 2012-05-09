/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.surftools.BeanstalkClient._
import com.surftools.BeanstalkClientImpl._
import akka.AkkaException
import akka.actor.ActorContext
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config
import akka.config.ConfigurationException
import akka.dispatch.MessageQueue
import akka.actor.ActorSystem

@deprecated("BeanstalkBasedMailbox will be removed in Akka 2.1", "2.0.2")
class BeanstalkBasedMailboxException(message: String) extends AkkaException(message) {}
@deprecated("BeanstalkBasedMailbox will be removed in Akka 2.1", "2.0.2")
class BeanstalkBasedMailboxType(systemSettings: ActorSystem.Settings, config: Config) extends MailboxType {
  private val settings = new BeanstalkMailboxSettings(systemSettings, config)
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new BeanstalkBasedMessageQueue(o, settings)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@deprecated("BeanstalkBasedMailbox will be removed in Akka 2.1", "2.0.2")
class BeanstalkBasedMessageQueue(_owner: ActorContext, val settings: BeanstalkMailboxSettings) extends DurableMessageQueue(_owner) with DurableMessageSerialization {

  private val messageSubmitDelaySeconds = settings.MessageSubmitDelay.toSeconds.toInt
  private val messageTimeToLiveSeconds = settings.MessageTimeToLive.toSeconds.toInt

  val log = Logging(system, "BeanstalkBasedMessageQueue")

  private val queue = new ThreadLocal[Client] { override def initialValue = connect(name) }

  // ===== For MessageQueue =====

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    queue.get.put(65536, messageSubmitDelaySeconds, messageTimeToLiveSeconds, serialize(envelope)).toInt
  }

  def dequeue(): Envelope = try {
    val job = queue.get.reserve(null)
    if (job eq null) null: Envelope
    else {
      val bytes = job.getData
      if (bytes ne null) {
        queue.get.delete(job.getJobId)
        deserialize(bytes)
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

  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = ()
}
