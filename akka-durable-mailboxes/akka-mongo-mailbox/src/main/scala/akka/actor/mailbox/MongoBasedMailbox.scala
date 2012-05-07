/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.AkkaException
import com.mongodb.async._
import com.mongodb.async.futures.RequestFutures
import org.bson.collection._
import akka.actor.ActorContext
import akka.event.Logging
import akka.actor.ActorRef
import java.util.concurrent.TimeoutException
import com.typesafe.config.Config
import akka.config.ConfigurationException
import akka.actor.ActorSystem
import akka.dispatch._
import scala.{ Some, None }

class MongoBasedMailboxException(message: String) extends AkkaException(message)

class MongoBasedMailboxType(systemSettings: ActorSystem.Settings, config: Config) extends MailboxType {
  private val settings = new MongoBasedMailboxSettings(systemSettings, config)
  override def create(owner: Option[ActorContext]): MessageQueue = owner match {
    case Some(o) ⇒ new MongoBasedMessageQueue(o, settings)
    case None    ⇒ throw new ConfigurationException("creating a durable mailbox requires an owner (i.e. does not work with BalancingDispatcher)")
  }
}

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
class MongoBasedMessageQueue(_owner: ActorContext, val settings: MongoBasedMailboxSettings) extends DurableMessageQueue(_owner, settings) {
  // this implicit object provides the context for reading/writing things as MongoDurableMessage
  implicit val mailboxBSONSer = new BSONSerializableMessageQueue(system)
  implicit val safeWrite = WriteConcern.Safe // TODO - Replica Safe when appropriate!

  private val dispatcher = owner.dispatcher

  val log = Logging(system, "MongoBasedMessageQueue")

  @volatile
  private var mongo = connect()

  def enqueue(receiver: ActorRef, envelope: Envelope) = {
    val asyncHandle = newAsyncCircuitBreakerHandle()
    /* TODO - Test if a BSON serializer is registered for the message and only if not, use toByteString? */
    val durableMessage = MongoDurableMessage(ownerPathString, envelope.message, envelope.sender)
    // todo - do we need to filter the actor name at all for safe collection naming?
    val result = Promise[Boolean]()(dispatcher)
    asyncHandle.withCircuitBreaker {
      mongo.insert(durableMessage, false)(RequestFutures.write { wr: Either[Throwable, (Option[AnyRef], WriteResult)] ⇒
        wr match {
          case Right((oid, wr)) ⇒ asyncHandle.onAsyncSuccess(); result.success(true)
          case Left(t)          ⇒ asyncHandle.onAsyncFailure(); result.failure(t)
        }
      })
      Await.ready(result, settings.WriteTimeout)
    }
  }

  def dequeue(): Envelope = {
    /**
     * Retrieves first item in natural order (oldest first, assuming no modification/move)
     * Waits 3 seconds for now for a message, else pops back out.
     * TODO - How do we handle fetch, but sleep if nothing is in there cleanly?
     * TODO - Should we have a specific query in place? Which way do we sort?
     * TODO - Error handling version!
     */
    val asyncHandle = newAsyncCircuitBreakerHandle()
    val envelopePromise = Promise[Envelope]()(dispatcher)
    asyncHandle.withCircuitBreaker {
      mongo.findAndRemove(Document.empty) { doc: Option[MongoDurableMessage] ⇒
        doc match {
          case Some(msg) ⇒
            envelopePromise.success(msg.envelope(system))
            ()
          case None ⇒
            log.info("No matching document found. Not an error, just an empty queue.")
            envelopePromise.success(null)
            ()
        }
      }
    }
    try {
      val result = Await.result(envelopePromise, settings.ReadTimeout)
      asyncHandle.onAsyncSuccess()
      result
    } catch { case _: TimeoutException ⇒ asyncHandle.onAsyncFailure(); null }
  }

  def numberOfMessages: Int = {
    val asyncHandle = newAsyncCircuitBreakerHandle()
    val count = Promise[Int]()(dispatcher)
    asyncHandle.withCircuitBreaker(mongo.count()(count.success))
    try {
      val result = Await.result(count, settings.ReadTimeout).asInstanceOf[Int]
      asyncHandle.onAsyncSuccess()
      result
    } catch { case _: Exception ⇒ asyncHandle.onAsyncFailure(); -1 }
  }

  //TODO review find other solution, this will be very expensive
  def hasMessages: Boolean = numberOfMessages > 0

  private[akka] def connect() = {
    log.info("CONNECTING mongodb uri : [{}]", settings.MongoURI)
    val _dbh = MongoConnection.fromURI(settings.MongoURI) match {
      case (conn, None, None) ⇒ {
        throw new UnsupportedOperationException("You must specify a database name to use with MongoDB; please see the MongoDB Connection URI Spec: 'http://www.mongodb.org/display/DOCS/Connections'")
      }
      case (conn, Some(db), Some(coll)) ⇒ {
        log.warning("Collection name ({}) specified in MongoURI Config will be used as a prefix for mailbox names", coll.name)
        db("%s.%s".format(coll.name, name))
      }
      case (conn, Some(db), None) ⇒ {
        db("mailbox.%s".format(name))
      }
      case default ⇒ throw new IllegalArgumentException("Illegal or unexpected response from Mongo Connection URI Parser: %s".format(default))
    }
    log.debug("CONNECTED to mongodb { dbh: '%s | %s'} ".format(_dbh, _dbh.name))
    _dbh
  }

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: Exception ⇒ {
        // TODO PN Question to Brendan: Is this the right approach to handle errors?
        // If connection to db is ok, but something else fails, then many connection pools will be created?
        // Doesn't the connection pool reconnect itself, or is this different in Hammersmith?
        // I guess one would like to use same connection pool for all mailboxes, not one for each mailbox. Is that handled under the hood?
        mongo = connect()
        body
      }
      case e: Exception ⇒ {
        val error = new MongoBasedMailboxException("Could not connect to MongoDB server, due to: " + e.getMessage())
        log.error(error, error.getMessage)
        throw error
      }
    }
  }

  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = ()
}
