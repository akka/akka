/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.mailbox

import akka.actor.ActorRef
import akka.config.Config.config
import akka.dispatch._
import akka.event.EventHandler
import akka.AkkaException

import MailboxProtocol._

import com.redis._

class RedisBasedMailboxException(message: String) extends AkkaException(message)

trait Base64StringEncoder {
  def byteArrayToString(bytes: Array[Byte]): String
  def stringToByteArray(str: String): Array[Byte]
}

object CommonsCodec {
  import org.apache.commons.codec.binary.Base64
  import org.apache.commons.codec.binary.Base64._

  val b64 = new Base64(true)

  trait CommonsCodecBase64StringEncoder {
    def byteArrayToString(bytes: Array[Byte]) = encodeBase64URLSafeString(bytes)
    def stringToByteArray(str: String) = b64.decode(str)
  }

  object Base64StringEncoder extends Base64StringEncoder with CommonsCodecBase64StringEncoder
}

import CommonsCodec._
import CommonsCodec.Base64StringEncoder._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RedisBasedMailbox(val owner: ActorRef) extends DurableExecutableMailbox(owner) {
  val nodes = config.getList("akka.persistence.redis.cluster")// need an explicit definition in akka-conf

  @volatile private var db = connect() //review Is the Redis connection thread safe?

  def enqueue(message: MessageInvocation) = {
    EventHandler.debug(this,
      "\nENQUEUING message in redis-based mailbox [%s]".format(message))
    withErrorHandling {
      db.rpush(name, byteArrayToString(serialize(message)))
    }
  }

  def dequeue: MessageInvocation = withErrorHandling {
    try {
      val item = db.lpop(name).map(stringToByteArray(_)).getOrElse(throw new NoSuchElementException(name + " not present"))
      val messageInvocation = deserialize(item)
      EventHandler.debug(this,
        "\nDEQUEUING message in redis-based mailbox [%s]".format(messageInvocation))
      messageInvocation
    } catch {
      case e: java.util.NoSuchElementException => null
      case e =>
        EventHandler.error(e, this, "Couldn't dequeue from Redis-based mailbox")
        throw e
    }
  }

  def size: Int = withErrorHandling {
    db.llen(name).getOrElse(throw new NoSuchElementException(name + " not present"))
  }

  def isEmpty: Boolean = size == 0 //TODO review find other solution, this will be very expensive

  private[akka] def connect() =
    nodes match {
      case Seq() =>
        // no cluster defined
        new RedisClient(
          config.getString("akka.actor.mailbox.redis.hostname", "127.0.0.1"),
          config.getInt("akka.actor.mailbox.redis.port", 6379))

      case s =>
        // with cluster
        import com.redis.cluster._
        EventHandler.info(this, "Running on Redis cluster")
        new RedisCluster(nodes: _*) {
          val keyTag = Some(NoOpKeyTag)
        }
    }

  private def withErrorHandling[T](body: => T): T = {
    try {
      body
    } catch {
      case e: RedisConnectionException => {
        db = connect()
        body
      }
      case e =>
        val error = new RedisBasedMailboxException("Could not connect to Redis server")
        EventHandler.error(error, this, "Could not connect to Redis server")
        throw error
    }
  }
}
