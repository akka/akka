/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.redis._
import akka.AkkaException
import akka.actor.ActorContext
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config
import akka.util.NonFatal
import akka.config.ConfigurationException
import akka.dispatch.MessageQueue

class RedisBasedMailboxException(message: String) extends AkkaException(message)

class RedisBasedMailboxType(config: Config) extends MailboxType {
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new RedisBasedMessageQueue(o, config)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

class RedisBasedMessageQueue(_owner: ActorContext, _config: Config) extends DurableMessageQueue(_owner) with DurableMessageSerialization {

  private val settings = new RedisBasedMailboxSettings(owner.system, _config)

  @volatile
  private var clients = connect() // returns a RedisClientPool for multiple asynchronous message handling

  val log = Logging(system, "RedisBasedMessageQueue")

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    withErrorHandling {
      clients.withClient { client ⇒
        client.rpush(name, serialize(envelope))
      }
    }
  }

  def dequeue(): Envelope = withErrorHandling {
    try {
      import serialization.Parse.Implicits.parseByteArray
      val item = clients.withClient { _.lpop[Array[Byte]](name).getOrElse(throw new NoSuchElementException(name + " not present")) }
      deserialize(item)
    } catch {
      case e: java.util.NoSuchElementException ⇒ null
      case NonFatal(e) ⇒
        log.error(e, "Couldn't dequeue from Redis-based mailbox")
        throw e
    }
  }

  def numberOfMessages: Int = withErrorHandling {
    clients.withClient { client ⇒
      client.llen(name).getOrElse(throw new NoSuchElementException(name + " not present"))
    }
  }

  def hasMessages: Boolean = numberOfMessages > 0 //TODO review find other solution, this will be very expensive

  private[akka] def connect() = {
    new RedisClientPool(settings.Hostname, settings.Port)
  }

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: RedisConnectionException ⇒ {
        clients = connect()
        body
      }
      case NonFatal(e) ⇒
        val error = new RedisBasedMailboxException("Could not connect to Redis server, due to: " + e.getMessage)
        log.error(error, error.getMessage)
        throw error
    }
  }

  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = ()
}

