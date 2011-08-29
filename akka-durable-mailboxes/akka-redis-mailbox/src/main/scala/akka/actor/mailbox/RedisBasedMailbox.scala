/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.LocalActorRef
import akka.config.Config.config
import akka.dispatch._
import akka.event.EventHandler
import akka.AkkaException

import MailboxProtocol._

import com.redis._

class RedisBasedMailboxException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RedisBasedMailbox(val owner: LocalActorRef) extends DurableExecutableMailbox(owner) {
  @volatile
  private var clients = connect() // returns a RedisClientPool for multiple asynchronous message handling

  def enqueue(message: MessageInvocation) = {
    EventHandler.debug(this,
      "\nENQUEUING message in redis-based mailbox [%s]".format(message))
    withErrorHandling {
      clients.withClient { client ⇒
        client.rpush(name, serialize(message))
      }
    }
  }

  def dequeue: MessageInvocation = withErrorHandling {
    try {
      import serialization.Parse.Implicits.parseByteArray
      val item = clients.withClient { client ⇒
        client.lpop[Array[Byte]](name).getOrElse(throw new NoSuchElementException(name + " not present"))
      }
      val messageInvocation = deserialize(item)
      EventHandler.debug(this,
        "\nDEQUEUING message in redis-based mailbox [%s]".format(messageInvocation))
      messageInvocation
    } catch {
      case e: java.util.NoSuchElementException ⇒ null
      case e ⇒
        EventHandler.error(e, this, "Couldn't dequeue from Redis-based mailbox")
        throw e
    }
  }

  def size: Int = withErrorHandling {
    clients.withClient { client ⇒
      client.llen(name).getOrElse(throw new NoSuchElementException(name + " not present"))
    }
  }

  def isEmpty: Boolean = size == 0 //TODO review find other solution, this will be very expensive

  private[akka] def connect() = {
    new RedisClientPool(
      config.getString("akka.actor.mailbox.redis.hostname", "127.0.0.1"),
      config.getInt("akka.actor.mailbox.redis.port", 6379))
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
        val error = new RedisBasedMailboxException("Could not connect to Redis server")
        EventHandler.error(error, this, "Could not connect to Redis server")
        throw error
    }
  }
}

