/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.redis._
import akka.actor.LocalActorRef
import akka.AkkaException
import akka.actor.ActorCell
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef

class RedisBasedMailboxException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RedisBasedMailbox(val owner: ActorCell) extends DurableMailbox(owner) with DurableMessageSerialization {
  @volatile
  private var clients = connect() // returns a RedisClientPool for multiple asynchronous message handling

  val log = Logging(system, this)

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in redis-based mailbox [%s]".format(envelope))
    withErrorHandling {
      clients.withClient { client ⇒
        client.rpush(name, serialize(envelope))
      }
    }
  }

  def dequeue(): Envelope = withErrorHandling {
    try {
      import serialization.Parse.Implicits.parseByteArray
      val item = clients.withClient { client ⇒
        client.lpop[Array[Byte]](name).getOrElse(throw new NoSuchElementException(name + " not present"))
      }
      val envelope = deserialize(item)
      log.debug("DEQUEUING message in redis-based mailbox [%s]".format(envelope))
      envelope
    } catch {
      case e: java.util.NoSuchElementException ⇒ null
      case e ⇒
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
    new RedisClientPool(
      system.settings.config.getString("akka.actor.mailbox.redis.hostname"),
      system.settings.config.getInt("akka.actor.mailbox.redis.port"))
  }

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: RedisConnectionException ⇒ {
        clients = connect()
        body
      }
      case e ⇒
        val error = new RedisBasedMailboxException("Could not connect to Redis server, due to: " + e.getMessage)
        log.error(error, error.getMessage)
        throw error
    }
  }
}

