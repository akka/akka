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

import com.mongodb.async._
import com.mongodb.async.futures.RequestFutures
import org.bson.collection._ 

class MongoBasedMailboxException(message: String) extends AkkaException(message)

/**
 * A "naive" durable mailbox which uses findAndRemove; it's possible if the actor crashes
 * after consuming a message that the message could be lost.
 *
 * Does not use the Protobuf protocol, instead using a pure Mongo based serialization for sanity
 * (and mongo-iness).
 *
 * TODO - Integrate Salat or a Salat-Based solution for the case classiness
 *
 * @author <a href="http://evilmonkeylabs.com">Brendan W. McAdams</a>
 */
class MongoBasedNaiveMailbox(val owner: ActorRef) extends DurableExecutableMailbox(owner) {
  // this implicit object provides the context for reading/writing things as MongoDurableMessage
  implicit val mailboxBSONSer = BSONSerializableMailbox
  implicit val safeWrite = WriteConcern.Safe // TODO - Replica Safe when appropriate!

  val mongoConfig = config.getList("akka.mailbox.actor.mailbox.mongodb")// need an explicit definition in akka-conf

  @volatile private var db = connect() //review Is the Redis connection thread safe?
  private val collName = "akka_mailbox.%s".format(name)

  def enqueue(msg: MessageInvocation) = {
    EventHandler.debug(this,
      "\nENQUEUING message in mongodb-based mailbox [%s]".format(msg))
    /* TODO - Test if a BSON serializer is registered for the message and only if not, use toByteString? */
    val durableMessage = MongoDurableMessage(ownerAddress, msg.receiver, msg.message, msg.channel)
    // todo - do we need to filter the actor name at all for safe collection naming?
    val result = new DefaultPromise[Boolean](10000) // give the write 10 seconds to succeed ... should we wait infinitely (does akka allow it?)
    db.insert(collName)(durableMessage, false)(RequestFutures.write { wr: Either[Throwable, (Option[AnyRef], WriteResult)] => wr match { 
      case Right((oid, wr)) => result.completeWithResult(true)
      case Left(t) => result.completeWithException(t)
    }})

    result.as[Boolean].orNull
  }

  def dequeue: MessageInvocation = withErrorHandling {
    /** 
     * Retrieves first item in natural order (oldest first, assuming no modification/move)
     * Waits 3 seconds for now for a message, else pops back out.
     * TODO - How do we handle fetch, but sleep if nothing is in there cleanly?
     * TODO - Should we have a specific query in place? Which way do we sort? 
     * TODO - Error handling version!
     */
    val msgInvocation = new DefaultPromise[MessageInvocation](10000)
    db.findAndRemove(collName)(Document.empty) { msg: MongoDurableMessage => 
      EventHandler.debug(this, 
        "\nDEQUEUING message in mongo-based mailbox [%s]".format(msg))
      msgInvocation.completeWithResult(msg.messageInvocation())
      EventHandler.debug(this, 
        "\nDEQUEUING messageInvocation in mongo-based mailbox [%s]".format(msgInvocation))
    }
    msgInvocation.as[MessageInvocation].orNull
  } 

  def size: Int = {
    val count = new DefaultPromise[Int](10000)
    db.count(collName)()(count.completeWithResult)
    count.as[Int].getOrElse(-1)
  }


  def isEmpty: Boolean = size == 0 //TODO review find other solution, this will be very expensive

  private[akka] def connect() = {
    EventHandler.debug(this,
      "\nCONNECTING mongodb { config: [%s] } ".format(mongoConfig))
    MongoConnection("localhost", 27017)("akka")
    /*nodes match {
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
    }*/
  }

  private def withErrorHandling[T](body: => T): T = {
    try {
      body
    } catch {
      case e: Exception => {
        db = connect()
        body
      }
      case e => {
        val error = new MongoBasedMailboxException("Could not connect to MongoDB server")
        EventHandler.error(error, this, "Could not connect to MongoDB server")
        throw error
      }
    }
  }
}
