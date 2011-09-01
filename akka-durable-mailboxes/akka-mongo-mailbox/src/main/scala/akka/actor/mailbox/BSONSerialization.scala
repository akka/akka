/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.{ Actor, ActorRef, LocalActorRef, NullChannel, Channel }
import akka.config.Config.config
import akka.dispatch._
import akka.event.EventHandler
import akka.AkkaException
import akka.cluster.MessageSerializer
import akka.cluster.RemoteProtocol.MessageProtocol

import MailboxProtocol._

import com.mongodb.async._

import org.bson.util._
import org.bson.io.{ BasicOutputBuffer, OutputBuffer }
import org.bson.types.ObjectId
import java.io.{ ByteArrayInputStream, InputStream }

import org.bson._
import org.bson.collection._

object BSONSerializableMailbox extends SerializableBSONObject[MongoDurableMessage] with Logging {

  protected[akka] def serializeDurableMsg(msg: MongoDurableMessage)(implicit serializer: BSONSerializer) = {
    EventHandler.debug(this, "Serializing a durable message to MongoDB: %s".format(msg))
    val msgData = MessageSerializer.serialize(msg.message.asInstanceOf[AnyRef])
    EventHandler.debug(this, "Serialized Message: %s".format(msgData))

    // TODO - Skip the whole map creation step for performance, fun, and profit! (Needs Salat)
    val b = Map.newBuilder[String, Any]
    b += "_id" -> msg._id
    b += "ownerAddress" -> msg.ownerAddress

    msg.channel match {
      case a: ActorRef ⇒ { b += "senderAddress" -> a.address }
      case _           ⇒ ()
    }
    /**
     * TODO - Figure out a way for custom serialization of the message instance
     * TODO - Test if a serializer is registered for the message and if not, use toByteString
     */
    b += "message" -> new org.bson.types.Binary(0, msgData.toByteArray)
    val doc = b.result
    EventHandler.debug(this, "Serialized Document: %s".format(doc))
    serializer.putObject(doc)
  }

  /*
   * TODO - Implement some object pooling for the Encoders/decoders
   */
  def encode(msg: MongoDurableMessage, out: OutputBuffer) = {
    implicit val serializer = new DefaultBSONSerializer
    serializer.set(out)
    serializeDurableMsg(msg)
    serializer.done
  }

  def encode(msg: MongoDurableMessage): Array[Byte] = {
    implicit val serializer = new DefaultBSONSerializer
    val buf = new BasicOutputBuffer
    serializer.set(buf)
    serializeDurableMsg(msg)
    val bytes = buf.toByteArray
    serializer.done
    bytes
  }

  def decode(in: InputStream): MongoDurableMessage = {
    val deserializer = new DefaultBSONDeserializer
    // TODO - Skip the whole doc step for performance, fun, and profit! (Needs Salat / custom Deser)
    val doc: BSONDocument = deserializer.decodeAndFetch(in).asInstanceOf[BSONDocument]
    EventHandler.debug(this, "Deserializing a durable message from MongoDB: %s".format(doc))

    val ownerAddress = doc.as[String]("ownerAddress")
    val owner = Actor.registry.actorFor(ownerAddress) match {
      case Some(l: LocalActorRef) ⇒ l
      case Some(a)                ⇒ throw new DurableMailboxException("Recipient of message is not a LocalActorRef: " + a)
      case None                   ⇒ throw new DurableMailboxException("No actor could be found for address [" + ownerAddress + "], could not deserialize message.")
    }

    val msgData = MessageProtocol.parseFrom(doc.as[org.bson.types.Binary]("message").getData)
    val msg = MessageSerializer.deserialize(msgData)

    val sender = if (doc.contains("senderAddress"))
      Actor.registry.actorFor(doc.as[String]("senderAddress"))
    else
      None

    MongoDurableMessage(ownerAddress, owner, msg, sender.getOrElse(NullChannel))
  }

  def checkObject(msg: MongoDurableMessage, isQuery: Boolean = false) = {} // object expected to be OK with this message type.

  def checkKeys(msg: MongoDurableMessage) {} // keys expected to be OK with this message type.

  /**
   * Checks for an ID and generates one.
   * Not all implementers will need this, but it gets invoked nonetheless
   * as a signal to BSONDocument, etc implementations to verify an id is there
   * and generate one if needed.
   */
  def checkID(msg: MongoDurableMessage) = msg // OID already generated in wrapper message

  def _id(msg: MongoDurableMessage): Option[AnyRef] = Some(msg._id)
}

// vim: set ts=2 sw=2 sts=2 et:
