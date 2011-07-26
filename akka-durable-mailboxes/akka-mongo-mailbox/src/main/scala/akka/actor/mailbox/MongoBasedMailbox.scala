/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
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

  val URI_CONFIG_KEY = "akka.actor.mailbox.mongodb.uri"
  val WRITE_TIMEOUT_KEY = "akka.actor.mailbox.mongodb.timeout.write"
  val READ_TIMEOUT_KEY = "akka.actor.mailbox.mongodb.timeout.read"
  val mongoURI = config.getString(URI_CONFIG_KEY)
  val writeTimeout = config.getInt(WRITE_TIMEOUT_KEY, 3000)
  val readTimeout = config.getInt(READ_TIMEOUT_KEY, 3000)

  @volatile
  private var mongo = connect()

  def enqueue(msg: MessageInvocation) = {
    EventHandler.debug(this,
      "\nENQUEUING message in mongodb-based mailbox [%s]".format(msg))
    /* TODO - Test if a BSON serializer is registered for the message and only if not, use toByteString? */
    val durableMessage = MongoDurableMessage(ownerAddress, msg.receiver, msg.message, msg.channel)
    // todo - do we need to filter the actor name at all for safe collection naming?
    val result = new DefaultPromise[Boolean](writeTimeout)
    mongo.insert(durableMessage, false)(RequestFutures.write { wr: Either[Throwable, (Option[AnyRef], WriteResult)] ⇒
      wr match {
        case Right((oid, wr)) ⇒ result.completeWithResult(true)
        case Left(t)          ⇒ result.completeWithException(t)
      }
    })

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
    val msgInvocation = new DefaultPromise[MessageInvocation](readTimeout)
    mongo.findAndRemove(Document.empty) { doc: Option[MongoDurableMessage] ⇒
      doc match {
        case Some(msg) ⇒ {
          EventHandler.debug(this,
            "\nDEQUEUING message in mongo-based mailbox [%s]".format(msg))
          msgInvocation.completeWithResult(msg.messageInvocation())
          EventHandler.debug(this,
            "\nDEQUEUING messageInvocation in mongo-based mailbox [%s]".format(msgInvocation))
        }
        case None ⇒
          {
            EventHandler.info(this,
              "\nNo matching document found. Not an error, just an empty queue.")
            msgInvocation.completeWithResult(null)
          }
          ()
      }
    }
    msgInvocation.as[MessageInvocation].orNull
  }

  def size: Int = {
    val count = new DefaultPromise[Int](readTimeout)
    mongo.count()(count.completeWithResult)
    count.as[Int].getOrElse(-1)
  }

  def isEmpty: Boolean = size == 0 //TODO review find other solution, this will be very expensive

  private[akka] def connect() = {
    require(mongoURI.isDefined, "Mongo URI (%s) must be explicitly defined in akka.conf; will not assume defaults for safety sake.".format(URI_CONFIG_KEY))
    EventHandler.info(this,
      "\nCONNECTING mongodb { uri : [%s] } ".format(mongoURI))
    val _dbh = MongoConnection.fromURI(mongoURI.get) match {
      case (conn, None, None) ⇒ {
        throw new UnsupportedOperationException("You must specify a database name to use with MongoDB; please see the MongoDB Connection URI Spec: 'http://www.mongodb.org/display/DOCS/Connections'")
      }
      case (conn, Some(db), Some(coll)) ⇒ {
        EventHandler.warning(this,
          "\nCollection name (%s) specified in MongoURI Config will be used as a prefix for mailbox names".format(coll.name))
        db("%s.%s".format(coll.name, name))
      }
      case (conn, Some(db), None) ⇒ {
        db("mailbox.%s".format(name))
      }
      case default ⇒ throw new IllegalArgumentException("Illegal or unexpected response from Mongo Connection URI Parser: %s".format(default))
    }
    EventHandler.debug(this,
      "\nCONNECTED to mongodb { dbh: '%s | %s'} ".format(_dbh, _dbh.name))
    _dbh
  }

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: Exception ⇒ {
        mongo = connect()
        body
      }
      case e ⇒ {
        val error = new MongoBasedMailboxException("Could not connect to MongoDB server")
        EventHandler.error(error, this, "Could not connect to MongoDB server")
        throw error
      }
    }
  }
}
