/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.AkkaException
import com.mongodb.async._
import com.mongodb.async.futures.RequestFutures
import org.bson.collection._
import akka.actor.ActorCell
import akka.dispatch.Envelope
import akka.event.Logging
import akka.dispatch.DefaultPromise
import akka.actor.ActorRef

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
class MongoBasedMailbox(val owner: ActorCell) extends DurableMailbox(owner) {
  // this implicit object provides the context for reading/writing things as MongoDurableMessage
  implicit val mailboxBSONSer = new BSONSerializableMailbox(system)
  implicit val safeWrite = WriteConcern.Safe // TODO - Replica Safe when appropriate!

  private val settings = MongoBasedMailboxExtension(owner.system)

  val log = Logging(system, "MongoBasedMailbox")

  @volatile
  private var mongo = connect()

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in mongodb-based mailbox [{}]", envelope)
    /* TODO - Test if a BSON serializer is registered for the message and only if not, use toByteString? */
    val durableMessage = MongoDurableMessage(ownerPathString, envelope.message, envelope.sender)
    // todo - do we need to filter the actor name at all for safe collection naming?
    val result = new DefaultPromise[Boolean](settings.WriteTimeout)(dispatcher)
    mongo.insert(durableMessage, false)(RequestFutures.write { wr: Either[Throwable, (Option[AnyRef], WriteResult)] ⇒
      wr match {
        case Right((oid, wr)) ⇒ result.completeWithResult(true)
        case Left(t)          ⇒ result.completeWithException(t)
      }
    })

    result.as[Boolean].orNull
  }

  def dequeue(): Envelope = withErrorHandling {
    /**
     * Retrieves first item in natural order (oldest first, assuming no modification/move)
     * Waits 3 seconds for now for a message, else pops back out.
     * TODO - How do we handle fetch, but sleep if nothing is in there cleanly?
     * TODO - Should we have a specific query in place? Which way do we sort?
     * TODO - Error handling version!
     */
    val envelopePromise = new DefaultPromise[Envelope](settings.ReadTimeout)(dispatcher)
    mongo.findAndRemove(Document.empty) { doc: Option[MongoDurableMessage] ⇒
      doc match {
        case Some(msg) ⇒ {
          log.debug("DEQUEUING message in mongo-based mailbox [{}]", msg)
          envelopePromise.completeWithResult(msg.envelope())
          log.debug("DEQUEUING messageInvocation in mongo-based mailbox [{}]", envelopePromise)
        }
        case None ⇒
          {
            log.info("No matching document found. Not an error, just an empty queue.")
            envelopePromise.completeWithResult(null)
          }
          ()
      }
    }
    envelopePromise.as[Envelope].orNull
  }

  def numberOfMessages: Int = {
    val count = new DefaultPromise[Int](settings.ReadTimeout)(dispatcher)
    mongo.count()(count.completeWithResult)
    count.as[Int].getOrElse(-1)
  }

  //TODO review find other solution, this will be very expensive
  def hasMessages: Boolean = numberOfMessages > 0

  private[akka] def connect() = {
    require(settings.MongoURI.isDefined, "Mongo URI (%s) must be explicitly defined in akka.conf; will not assume defaults for safety sake.".format(settings.UriConfigKey))
    log.info("CONNECTING mongodb uri : [{}]", settings.MongoURI)
    val _dbh = MongoConnection.fromURI(settings.MongoURI.get) match {
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
}
